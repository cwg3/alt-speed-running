// Backfills seenSeeds from match history.
//
//   npx tsx scripts/backfillSeenSeeds.ts <pool> <matches> <players> [--apply]
//
// Seeds used to be consumed: a claim set used = true and the pair was
// finished. They are not any more - fairness only needs that neither
// player in a match has PLAYED the seed before - so rows retired by
// finished matches are drawable again.
//
// Which means the players who already played them need to be recorded
// as having seen them. Without this the first thing the new scheme
// would do is deal somebody a seed they have run before, which is the
// one outcome it exists to prevent.
//
// Older matches stored only the seeds, not the pool row, so they are
// mapped back by overworldSeed. Dry run by default.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

async function scanAll(table: string, projection: string) {
	const out: Record<string, any>[] = [];
	let startKey: Record<string, any> | undefined;
	do {
		const page: any = await ddb.send(new ScanCommand({
			TableName: table,
			ProjectionExpression: projection,
			ExclusiveStartKey: startKey,
		}));
		out.push(...(page.Items ?? []));
		startKey = page.LastEvaluatedKey;
	} while (startKey);
	return out;
}

async function main() {
	const [pool, matches, players] = process.argv.slice(2);
	const apply = process.argv.includes('--apply');
	if (!pool || !matches || !players) {
		console.error('usage: backfillSeenSeeds.ts <pool> <matches> <players> [--apply]');
		process.exit(2);
	}

	const poolRows = await scanAll(pool, 'seedPairId, overworldSeed');
	const bySeed = new Map<string, string>();
	for (const r of poolRows) bySeed.set(String(r.overworldSeed), r.seedPairId);
	console.log(`${poolRows.length} pool rows`);

	const matchRows = await scanAll(matches, 'matchId, players, overworldSeed, seedPairId');
	console.log(`${matchRows.length} matches`);

	// uuid -> set of seedPairIds
	const seen = new Map<string, Set<string>>();
	let unmapped = 0;
	for (const m of matchRows) {
		const pairId = m.seedPairId ?? bySeed.get(String(m.overworldSeed));
		if (!pairId) { unmapped++; continue; }
		for (const p of (m.players ?? [])) {
			if (!p?.uuid) continue;
			if (!seen.has(p.uuid)) seen.set(p.uuid, new Set());
			seen.get(p.uuid)!.add(pairId);
		}
	}
	console.log(`${seen.size} players with history; ${unmapped} matches whose seed is no longer in the pool`);

	for (const [uuid, ids] of seen) {
		console.log(`  ${uuid}  ${ids.size} seeds`);
		if (apply) {
			await ddb.send(new UpdateCommand({
				TableName: players,
				Key: { uuid },
				UpdateExpression: 'ADD seenSeeds :s',
				ExpressionAttributeValues: { ':s': new Set(Array.from(ids)) },
			}));
		}
	}
	console.log(apply ? 'applied' : 'dry run - pass --apply to write');
}

main().catch((e) => { console.error(e); process.exit(1); });
