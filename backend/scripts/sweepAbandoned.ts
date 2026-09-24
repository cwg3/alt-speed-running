// Resolves matches both players walked away from.
//
//   npx tsx scripts/sweepAbandoned.ts <matches-table> <seed-pool-table> [--dry-run]
//
// The live poll resolves the common case on its own: whoever is still
// playing keeps their heartbeat fresh, sees the other has gone silent,
// and is awarded the match. That needs someone to be there.
//
// When BOTH players vanish - a crash on each side, or a test session
// that was simply closed - nobody polls, so nothing triggers and the
// match sits pending forever. It holds a seed out of the pool with it.
//
// Those are voided rather than decided. Neither player was present, so
// there is no one to award a win to and no one who deserves a loss;
// the same reasoning as a bad-seed void. The seed goes back to the
// pool, because nothing was wrong with it.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

// Matches the live poll's own threshold, doubled. A sweep is a blunt
// instrument run out of band, so it should never be the thing that
// decides a match someone is still in.
const ABANDON_MS = 20 * 60 * 1000;

async function main() {
	const matchesTable = process.argv[2];
	const poolTable = process.argv[3];
	const dryRun = process.argv.includes('--dry-run');
	// --match <id> voids one named match regardless of how long it has
	// been quiet. Testing restarts the client constantly, and every
	// restart leaves a live match that the client rejoins on launch -
	// so the queue is blocked for ABANDON_MS with no way out but
	// forfeiting in game. Three test runs were lost to that before this
	// existed.
	const matchFlag = process.argv.indexOf('--match');
	const onlyMatch = matchFlag >= 0 ? process.argv[matchFlag + 1] : null;
	if (!matchesTable || !poolTable) {
		console.error('usage: npx tsx scripts/sweepAbandoned.ts <matches-table> <seed-pool-table> [--dry-run]');
		process.exit(1);
	}

	const pending: Record<string, any>[] = [];
	let startKey: Record<string, unknown> | undefined;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: matchesTable,
			FilterExpression: '#s = :p',
			ExpressionAttributeNames: { '#s': 'status' },
			ExpressionAttributeValues: { ':p': 'pending' },
			ExclusiveStartKey: startKey,
		}));
		pending.push(...(page.Items ?? []));
		startKey = page.LastEvaluatedKey;
	} while (startKey);

	console.log(`${pending.length} pending match(es)`);

	const now = Date.now();
	let voided = 0;
	let live = 0;

	for (const match of pending) {
		const seen: Record<string, number> = match.lastSeenAt ?? {};
		const players: { uuid: string; username: string }[] = match.players ?? [];
		// A match with no heartbeat map predates it; fall back to when
		// it was created so those can still age out.
		const stamps = players.map((p) => seen[p.uuid] ?? match.createdAt ?? 0);
		const newest = stamps.length ? Math.max(...stamps) : 0;
		const silentFor = now - newest;

		if (onlyMatch && match.matchId !== onlyMatch) {
			continue;
		}
		if (!onlyMatch && silentFor <= ABANDON_MS) {
			live++;
			continue;
		}

		const who = players.map((p) => p.username).join(' vs ');
		console.log(`  ${match.matchId}  ${who}  silent ${Math.round(silentFor / 60000)} min`);
		if (dryRun) {
			voided++;
			continue;
		}

		await ddb.send(new UpdateCommand({
			TableName: matchesTable,
			Key: { matchId: match.matchId },
			UpdateExpression: 'SET #s = :void, completedAt = :now, voidReason = :reason',
			ConditionExpression: '#s = :pending',
			ExpressionAttributeNames: { '#s': 'status' },
			ExpressionAttributeValues: {
				':void': 'voided',
				':pending': 'pending',
				':now': now,
				':reason': 'abandoned by both players',
			},
		}));

		// Hand the seed back - nothing was wrong with it.
		const rows = await ddb.send(new ScanCommand({
			TableName: poolTable,
			FilterExpression: 'assignedMatchId = :m',
			ExpressionAttributeValues: { ':m': match.matchId },
			ProjectionExpression: 'seedPairId',
		}));
		for (const row of rows.Items ?? []) {
			await ddb.send(new UpdateCommand({
				TableName: poolTable,
				Key: { seedPairId: row.seedPairId },
				UpdateExpression: 'SET used = :false REMOVE assignedMatchId',
				ExpressionAttributeValues: { ':false': false },
			}));
		}
		voided++;
	}

	console.log(`${dryRun ? 'Would void' : 'Voided'} ${voided}; left ${live} still active`);
}

main().catch((err) => {
	console.error(err);
	process.exit(1);
});
