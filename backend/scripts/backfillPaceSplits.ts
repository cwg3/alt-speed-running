// Writes the pace's splits onto every finished paced match that predates
// matchCompletion recording them.
//
//   npx tsx scripts/backfillPaceSplits.ts <matches-table> [--apply]
//
// The pace's run was always on the row as paceSchedule; it was just
// never copied into `splits`, so the match page showed an empty column
// for it. The cut is the match's completedAt against the player's run
// start - the same rule matchCompletion now applies - so a backfilled
// row reads exactly as a new one would. Dry run unless --apply.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand } from '@aws-sdk/lib-dynamodb';
import { recordPaceSplits } from '../lambda/lib/matchCompletion';
import { paceSplitsAtEnd, Schedule } from '../lambda/lib/paceSchedule';
import { isSyntheticPlayer } from '../lambda/lib/seedPool';

const [table, flag] = process.argv.slice(2);
if (!table) {
	console.error('usage: backfillPaceSplits.ts <matches-table> [--apply]');
	process.exit(1);
}
const apply = flag === '--apply';
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

(async () => {
	let start: Record<string, unknown> | undefined;
	let n = 0;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: table,
			FilterExpression: 'attribute_exists(paceSchedule) AND #s <> :p',
			ExpressionAttributeNames: { '#s': 'status' },
			ExpressionAttributeValues: { ':p': 'pending' },
			ExclusiveStartKey: start,
		}));
		for (const m of page.Items ?? []) {
			const pace = (m.players ?? []).find((p: { uuid: string }) => isSyntheticPlayer(p.uuid));
			if (m.splits?.[pace?.uuid]) continue;
			// Voided matches carry no completedAt; nothing to cut against.
			if (typeof m.completedAt !== 'number') continue;
			const runner = (m.players ?? []).find((p: { uuid: string }) => !isSyntheticPlayer(p.uuid));
			const preview = paceSplitsAtEnd(
				m.paceSchedule as Schedule, m.runStarts?.[runner?.uuid], m.completedAt);
			console.log(m.matchId, Object.keys(preview).join(',') || '(none)');
			if (apply) await recordPaceSplits(table, m.matchId, m.completedAt);
			n++;
		}
		start = page.LastEvaluatedKey;
	} while (start);
	console.log(`${n} match(es) ${apply ? 'updated' : 'would be updated'}`);
})();
