// Frees seed pairs that are marked used but belong to no live match.
//
//   npx tsx scripts/releaseSeeds.ts <seed-pool-table> <matches-table> [--dry-run]
//
// SEEDS ARE NO LONGER CONSUMED BY A MATCH. claimSeedPair records a
// sighting per player and leaves `used` alone (lib/seedPool.ts), so
// `used = true` now means held pending verification or quarantined by a
// bad-seed vote - and neither is free to release. The scan below filters
// both out by name. What remains for this script is residue of the old
// consume-on-claim model, and rows pinned by hand for practice.
//
// This header described that old model long after the code stopped
// implementing it, and it was believed: it is why running the two-player
// integration test was thought to cost a pool seed per run. It does not
// cost anything.
//
// This releases any row whose assignedMatchId is absent, or names a
// match that is no longer pending. A row belonging to a match still in
// progress is left alone: handing that seed to someone else mid-match
// would put two players on the same world with different clocks.
//
// Deliberately a script rather than an inline loop. It is a bulk write
// over the whole pool, which is exactly the kind of thing that should
// be reviewable, re-runnable and dry-runnable rather than typed out
// once into a shell.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand, GetCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

async function main() {
	const poolTable = process.argv[2];
	const matchesTable = process.argv[3];
	const dryRun = process.argv.includes('--dry-run');
	if (!poolTable || !matchesTable) {
		console.error('usage: npx tsx scripts/releaseSeeds.ts <seed-pool-table> <matches-table> [--dry-run]');
		process.exit(1);
	}

	// Paginate on LastEvaluatedKey. A filtered scan applies Limit
	// before the filter, so an empty page does not mean the end.
	const claimed: { seedPairId: string; assignedMatchId?: string }[] = [];
	let startKey: Record<string, unknown> | undefined;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: poolTable,
			ProjectionExpression: 'seedPairId, assignedMatchId, used, heldUnverified, poolReject',
			// Held and quarantined rows are used = true as well, and
			// they are NOT free to release. Held means no generated
			// world has been looked at yet; quarantined means a player
			// voted the seed unplayable. Releasing either puts a pair
			// nobody has verified - or one already known bad - straight
			// into the drawable pool.
			//
			// This mattered less when used = true also meant "consumed
			// by a finished match", because most such rows really were
			// releasable. Seeds are no longer consumed, so used = true
			// now means ONLY held or quarantined, and without this
			// filter the script would release every one of them.
			FilterExpression: '#u = :true AND attribute_not_exists(heldUnverified) '
				+ 'AND attribute_not_exists(poolReject)',
			ExpressionAttributeNames: { '#u': 'used' },
			ExpressionAttributeValues: { ':true': true },
			ExclusiveStartKey: startKey,
		}));
		for (const item of page.Items ?? []) {
			claimed.push({
				seedPairId: item.seedPairId,
				assignedMatchId: item.assignedMatchId,
			});
		}
		startKey = page.LastEvaluatedKey;
	} while (startKey);

	console.log(`${claimed.length} rows marked used`);

	// Cache match lookups: a pinned pool has hundreds of rows with no
	// assignedMatchId at all, and the few that have one often share it.
	const pending = new Map<string, boolean>();
	async function isPending(matchId: string): Promise<boolean> {
		if (pending.has(matchId)) {
			return pending.get(matchId)!;
		}
		const res = await ddb.send(new GetCommand({
			TableName: matchesTable,
			Key: { matchId },
			ProjectionExpression: '#s',
			ExpressionAttributeNames: { '#s': 'status' },
		}));
		const result = res.Item?.status === 'pending';
		pending.set(matchId, result);
		return result;
	}

	let released = 0;
	let kept = 0;
	for (const row of claimed) {
		if (row.assignedMatchId && await isPending(row.assignedMatchId)) {
			kept++;
			continue;
		}
		if (!dryRun) {
			await ddb.send(new UpdateCommand({
				TableName: poolTable,
				Key: { seedPairId: row.seedPairId },
				UpdateExpression: 'SET #u = :false REMOVE assignedMatchId',
				ExpressionAttributeNames: { '#u': 'used' },
				ExpressionAttributeValues: { ':false': false },
			}));
		}
		released++;
	}

	console.log(`${dryRun ? 'Would release' : 'Released'} ${released} rows`);
	console.log(`Kept ${kept} rows belonging to matches still in progress`);
}

main().catch((err) => {
	console.error(err);
	process.exit(1);
});
