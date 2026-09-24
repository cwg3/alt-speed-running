// Builds history rows from matches that completed before the history
// table existed.
//
//   npx tsx scripts/backfillMatchHistory.ts <matches> <history> [--apply]
//
// The history table is written by the settle path, so it only knows
// about matches finished since it was deployed. Without this, a
// player's history starts empty and their real games are invisible -
// and the first thing anyone does with a history screen is look for a
// match they remember.
//
// Rows are keyed by (uuid, completedAt), so re-running is safe: the
// same match writes the same row.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

async function main() {
	const [matchesTable, historyTable] = process.argv.slice(2);
	const apply = process.argv.includes('--apply');
	if (!matchesTable || !historyTable) {
		console.error('usage: backfillMatchHistory.ts <matches> <history> [--apply]');
		process.exit(2);
	}

	const matches: any[] = [];
	let startKey: Record<string, any> | undefined;
	do {
		const page: any = await ddb.send(new ScanCommand({
			TableName: matchesTable, ExclusiveStartKey: startKey,
		}));
		matches.push(...(page.Items ?? []));
		startKey = page.LastEvaluatedKey;
	} while (startKey);
	console.log(`${matches.length} matches scanned`);

	let written = 0, skipped = 0;
	for (const m of matches) {
		// Only finished matches belong in history. A pending or voided
		// one is not a game anybody played to a result.
		if (m.status !== 'completed' || !m.winnerUuid) { skipped++; continue; }
		const players: any[] = m.players ?? [];
		if (players.length !== 2) { skipped++; continue; }
		// completedAt is the sort key. Without it there is no row to
		// write - fall back to createdAt so old matches still land
		// somewhere sensible rather than being dropped.
		const completedAt: number = m.completedAt ?? m.createdAt ?? 0;
		if (!completedAt) { skipped++; continue; }

		const results = m.results ?? {};
		for (const me of players) {
			const them = players.find((p) => p.uuid !== me.uuid);
			const r = results[me.uuid] ?? {};
			if (apply) {
				await ddb.send(new UpdateCommand({
					TableName: historyTable,
					Key: { uuid: me.uuid, completedAt },
					UpdateExpression: 'SET matchId = :m, opponentUuid = :ou, opponentName = :on, '
						+ 'won = :w, ratingDelta = :d, seasonPointsAwarded = :p, '
						+ 'seedType = :st, overworldSeed = :os, netherSeed = :ns, '
						+ 'worldSetupVersion = :wsv, backfilled = :b',
					ExpressionAttributeValues: {
						':m': m.matchId,
						':ou': them?.uuid ?? 'unknown',
						':on': them?.username ?? 'opponent',
						':w': m.winnerUuid === me.uuid,
						':d': r.ratingDelta ?? 0,
						':p': r.seasonPointsAwarded ?? 0,
						':st': m.seedType ?? 'unknown',
						':os': m.overworldSeed ?? 0,
						':ns': m.netherSeed ?? 0,
						// 0 means "built before stamping existed", which
						// is not the same as version 0 - a replay must
						// refuse these rather than guess.
						':wsv': m.worldSetupVersion ?? 0,
						':b': true,
					},
				}));
			}
			written++;
		}
	}
	console.log(`${written} rows ${apply ? 'written' : 'would be written'}; ${skipped} matches skipped`);
	if (!apply) console.log('dry run - pass --apply to write');
}

main().catch((e) => { console.error(e); process.exit(1); });
