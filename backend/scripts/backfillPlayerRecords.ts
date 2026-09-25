// Fills in wins/losses/forfeits/matches on player rows from match history.
//
//   npx tsx scripts/backfillPlayerRecords.ts <players-table> <history-table> [--apply]
//
// WHY THIS IS NEEDED. The counters are incremented on match completion,
// so they only ever describe matches played after they were added. Every
// player who had already played showed zero, and the leaderboard omits
// players with no matches - correctly, since that is an absence of a
// result rather than a result - so the board came up empty on a ladder
// that had been played for weeks.
//
// SYNTHETIC ROWS ARE NOT COUNTED. Most history rows carry
// backfilled=true: they were generated to exercise the history screen,
// not played. Counting them would publish a fabricated win-loss record
// on the standings, which is the specific thing this project exists not
// to do. A real record of one win is worth more than an invented record
// of fifty.
//
// FORFEITS CANNOT BE RECOVERED for most rows. forfeitedBy was added to
// the history row later, so older losses cannot be told apart from older
// forfeits. They are counted as losses, which is what they were recorded
// as at the time - not guessed at.
//
// Idempotent: SET, not ADD. Running it twice writes the same numbers.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, QueryCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

async function main() {
	const playersTable = process.argv[2];
	const historyTable = process.argv[3];
	const apply = process.argv.includes('--apply');
	if (!playersTable || !historyTable) {
		console.error('usage: backfillPlayerRecords.ts <players-table> <history-table> [--apply]');
		process.exit(1);
	}

	const players: Record<string, unknown>[] = [];
	let startKey: Record<string, unknown> | undefined;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: playersTable,
			ProjectionExpression: '#u, username',
			ExpressionAttributeNames: { '#u': 'uuid' },
			ExclusiveStartKey: startKey,
		}));
		players.push(...((page.Items ?? []) as Record<string, unknown>[]));
		startKey = page.LastEvaluatedKey as Record<string, unknown> | undefined;
	} while (startKey);

	console.log(`${players.length} player row(s)${apply ? '' : '  (dry run - pass --apply to write)'}\n`);

	for (const p of players) {
		const uuid = String(p.uuid);
		const rows: Record<string, unknown>[] = [];
		let hk: Record<string, unknown> | undefined;
		do {
			const page = await ddb.send(new QueryCommand({
				TableName: historyTable,
				KeyConditionExpression: '#u = :u',
				ExpressionAttributeNames: { '#u': 'uuid' },
				ExpressionAttributeValues: { ':u': uuid },
				ExclusiveStartKey: hk,
			}));
			rows.push(...((page.Items ?? []) as Record<string, unknown>[]));
			hk = page.LastEvaluatedKey as Record<string, unknown> | undefined;
		} while (hk);

		const real = rows.filter((r) => r.backfilled !== true);
		let wins = 0, losses = 0, forfeits = 0;
		for (const r of real) {
			if (r.won === true) { wins += 1; continue; }
			if (typeof r.forfeitedBy === 'string' && r.forfeitedBy === uuid) { forfeits += 1; continue; }
			losses += 1;
		}
		const matches = real.length;

		console.log(`  ${String(p.username ?? uuid).padEnd(16)} `
			+ `${matches} real of ${rows.length} rows  ->  `
			+ `W${wins} L${losses} F${forfeits}`
			+ (rows.length - real.length > 0 ? `   (${rows.length - real.length} synthetic ignored)` : ''));

		if (apply) {
			await ddb.send(new UpdateCommand({
				TableName: playersTable,
				Key: { uuid },
				UpdateExpression: 'SET wins = :w, losses = :l, forfeits = :f, matches = :m',
				ExpressionAttributeValues: { ':w': wins, ':l': losses, ':f': forfeits, ':m': matches },
			}));
		}
	}
	console.log(apply ? '\nwritten' : '\nnothing written');
}

main().catch((e) => { console.error(e); process.exit(1); });
