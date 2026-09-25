// Invites players to the ladder, by Minecraft username.
//
//   npx tsx scripts/invite.ts <players-table> <username> [username...]
//   npx tsx scripts/invite.ts <players-table> --list
//   npx tsx scripts/invite.ts <players-table> --revoke <username>
//
// An invite is just a player row with allowed = true. Someone never
// invited has no row, so refusal is the default and no second table is
// needed.
//
// Usernames are resolved to UUIDs through Mojang, because the backend
// checks the VERIFIED uuid from the session handshake. A username is a
// convenience for whoever is doing the inviting; it is not identity,
// and people change theirs.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

async function uuidFor(username: string): Promise<{ id: string; name: string } | null> {
	const res = await fetch(
		`https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(username)}`);
	if (res.status === 200) {
		const j = await res.json() as { id: string; name: string };
		// Mojang returns the uuid undashed; the session handshake returns
		// it the same way, so no reformatting - they must match exactly.
		return j;
	}
	return null;
}

async function main() {
	const [table, ...rest] = process.argv.slice(2);
	if (!table || rest.length === 0) {
		console.error('usage: invite.ts <players-table> <username>... | --list | --revoke <username>');
		process.exit(2);
	}

	if (rest[0] === '--list') {
		let startKey: Record<string, any> | undefined;
		const rows: any[] = [];
		do {
			const page: any = await ddb.send(new ScanCommand({
				TableName: table,
				FilterExpression: '#a = :t',
				// #u, not uuid. 'uuid' is a DynamoDB reserved keyword and a
				// projection naming it directly is rejected outright - the
				// listing mode of this tool had never worked.
				ExpressionAttributeNames: { '#a': 'allowed', '#u': 'uuid' },
				ExpressionAttributeValues: { ':t': true },
				ProjectionExpression: '#u, username, invitedAt',
				ExclusiveStartKey: startKey,
			}));
			rows.push(...(page.Items ?? []));
			startKey = page.LastEvaluatedKey;
		} while (startKey);
		console.log(`${rows.length} invited`);
		for (const r of rows) {
			const when = r.invitedAt ? new Date(r.invitedAt).toISOString().slice(0, 10) : '-';
			console.log(`  ${r.username ?? '(unknown)'}  ${r.uuid}  ${when}`);
		}
		return;
	}

	const revoke = rest[0] === '--revoke';
	const names = revoke ? rest.slice(1) : rest;

	for (const name of names) {
		const profile = await uuidFor(name);
		if (!profile) {
			console.error(`  ${name}: no such Minecraft account - not changed`);
			continue;
		}
		await ddb.send(new UpdateCommand({
			TableName: table,
			Key: { uuid: profile.id },
			UpdateExpression: revoke
				? 'SET #a = :f, username = :n'
				: 'SET #a = :t, username = :n, invitedAt = if_not_exists(invitedAt, :now)',
			ExpressionAttributeNames: { '#a': 'allowed' },
			ExpressionAttributeValues: revoke
				? { ':f': false, ':n': profile.name }
				: { ':t': true, ':n': profile.name, ':now': Date.now() },
		}));
		console.log(`  ${revoke ? 'revoked' : 'invited'} ${profile.name} (${profile.id})`);
	}
}

main().catch((e) => { console.error(e); process.exit(1); });
