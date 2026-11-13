// Bans a player from the ladder, by Minecraft username.
//
//   npx tsx scripts/ban.ts <players-table> <sessions-table> <username> [username...]
//   npx tsx scripts/ban.ts <players-table> <sessions-table> --unban <username>
//
// Separate from an invite on purpose. Revoking an invite only matters
// while the allowlist is on; a ban holds either way, and is the only
// way to keep somebody off once it is off.
//
// A ban also DELETES the sessions the player already holds. Login is
// where the ban is checked, and a session lasts 30 days - without this
// a banned player keeps playing on the token they had.
//
// Usernames are resolved through Mojang for the same reason as
// invite.ts: the backend checks the VERIFIED uuid, and people change
// their names.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DeleteCommand, DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

async function uuidFor(username: string): Promise<{ id: string; name: string } | null> {
	const res = await fetch(
		`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(username)}`);
	if (res.status === 200) {
		return await res.json() as { id: string; name: string };
	}
	return null;
}

/** Deletes every session row for `uuid`. Returns how many. */
async function endSessions(sessionsTable: string, uuid: string): Promise<number> {
	let startKey: Record<string, any> | undefined;
	let ended = 0;
	do {
		const page: any = await ddb.send(new ScanCommand({
			TableName: sessionsTable,
			// #u: 'uuid' is a DynamoDB reserved keyword - see invite.ts.
			FilterExpression: '#u = :u',
			ExpressionAttributeNames: { '#u': 'uuid', '#t': 'token' },
			ExpressionAttributeValues: { ':u': uuid },
			ProjectionExpression: '#t',
			ExclusiveStartKey: startKey,
		}));
		for (const row of page.Items ?? []) {
			await ddb.send(new DeleteCommand({ TableName: sessionsTable, Key: { token: row.token } }));
			ended++;
		}
		startKey = page.LastEvaluatedKey;
	} while (startKey);
	return ended;
}

async function main() {
	const [players, sessions, ...rest] = process.argv.slice(2);
	if (!players || !sessions || rest.length === 0) {
		console.error('usage: ban.ts <players-table> <sessions-table> <username>... | --unban <username>...');
		process.exit(2);
	}

	const unban = rest[0] === '--unban';
	const names = unban ? rest.slice(1) : rest;

	for (const name of names) {
		const profile = await uuidFor(name);
		if (!profile) {
			console.error(`  ${name}: no such Minecraft account - not changed`);
			continue;
		}
		await ddb.send(new UpdateCommand({
			TableName: players,
			Key: { uuid: profile.id },
			UpdateExpression: unban
				? 'REMOVE banned, bannedAt SET username = :n'
				: 'SET banned = :t, bannedAt = :now, username = :n',
			ExpressionAttributeValues: unban
				? { ':n': profile.name }
				: { ':t': true, ':now': Date.now(), ':n': profile.name },
		}));
		if (unban) {
			console.log(`  unbanned ${profile.name} (${profile.id})`);
		} else {
			const ended = await endSessions(sessions, profile.id);
			console.log(`  banned ${profile.name} (${profile.id}), ended ${ended} session(s)`);
		}
	}
}

main().catch((e) => { console.error(e); process.exit(1); });
