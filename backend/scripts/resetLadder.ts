// Returns the ladder to a pre-play state.
//
//   npx tsx scripts/resetLadder.ts <players-table> <history-table> [--apply]
//
// Every player goes back to the starting rating with no points, no
// record, no split baseline and no seen seeds, and every match history
// row is deleted.
//
// WHY THIS EXISTS. Development played ~18 real matches against a bot and
// generated ~180 more to exercise the history screen. The rating that
// came out of that is not a measurement of anything: it was moved by
// games that were not played, against an opponent that is not ranked. A
// ladder about to take testers should not open with a number it cannot
// justify, and "1229" beside a record of one win would have to be
// explained to everyone who saw it.
//
// splitStats goes too. It is the anti-cheat baseline - a per-split mean
// and variance built from previous runs - so a baseline learned from
// synthetic matches would judge real ones against fiction. It rebuilds
// itself from the first genuine runs.
//
// seenSeeds goes as well, by explicit choice. It enforces "nobody is
// dealt the same seed twice", so clearing it means a previously raced
// world can come round again. At 284 seeds and a handful of plays that
// is a small price for a clean start, but it IS the one thing here that
// weakens a fairness guarantee rather than just discarding noise.
//
// DynamoDB has point-in-time recovery on both tables, 35 days, so this
// is recoverable - but only by restoring to a new table and copying
// rows back, which is an hour of work nobody wants. Run the dry run.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand, BatchWriteCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const STARTING_RATING = 1500;

async function scanAll(table: string, projection: string, names?: Record<string, string>) {
	const out: Record<string, unknown>[] = [];
	let startKey: Record<string, unknown> | undefined;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: table,
			ProjectionExpression: projection,
			...(names ? { ExpressionAttributeNames: names } : {}),
			ExclusiveStartKey: startKey,
		}));
		out.push(...((page.Items ?? []) as Record<string, unknown>[]));
		startKey = page.LastEvaluatedKey as Record<string, unknown> | undefined;
	} while (startKey);
	return out;
}

async function main() {
	const playersTable = process.argv[2];
	const historyTable = process.argv[3];
	const apply = process.argv.includes('--apply');
	if (!playersTable || !historyTable) {
		console.error('usage: resetLadder.ts <players-table> <history-table> [--apply]');
		process.exit(1);
	}

	const players = await scanAll(playersTable, '#u, username, skillRating, seasonPoints',
		{ '#u': 'uuid' });
	const history = await scanAll(historyTable, '#u, completedAt', { '#u': 'uuid' });

	console.log(`${players.length} player(s), ${history.length} history row(s)`
		+ `${apply ? '' : '   (DRY RUN - pass --apply to write)'}\n`);

	for (const p of players) {
		console.log(`  ${String(p.username ?? p.uuid).padEnd(16)} `
			+ `rating ${p.skillRating} -> ${STARTING_RATING}, points ${p.seasonPoints} -> 0, `
			+ `record -> 0-0-0, splitStats and seenSeeds cleared`);
		if (apply) {
			await ddb.send(new UpdateCommand({
				TableName: playersTable,
				Key: { uuid: String(p.uuid) },
				UpdateExpression:
					'SET skillRating = :r, seasonPoints = :z, wins = :z, losses = :z, '
					+ 'forfeits = :z, matches = :z REMOVE splitStats, seenSeeds',
				ExpressionAttributeValues: { ':r': STARTING_RATING, ':z': 0 },
			}));
		}
	}

	console.log(`\n  history: ${history.length} row(s) to delete`);
	if (apply) {
		// BatchWrite caps at 25.
		for (let i = 0; i < history.length; i += 25) {
			const chunk = history.slice(i, i + 25);
			await ddb.send(new BatchWriteCommand({
				RequestItems: {
					[historyTable]: chunk.map((h) => ({
						DeleteRequest: { Key: { uuid: String(h.uuid), completedAt: Number(h.completedAt) } },
					})),
				},
			}));
		}
		console.log('  deleted');
	}

	console.log(apply ? '\nladder reset' : '\nnothing written');
}

main().catch((e) => { console.error(e); process.exit(1); });
