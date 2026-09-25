// Lists seeds that players voted unplayable, for filter review.
//
//   npx tsx scripts/reviewBadSeeds.ts <seed-pool-table> [--json]
//
// Every row here is a filter bug with evidence attached. Two players
// who were racing each other agreed the seed could not be run, which is
// a stronger signal than anything the pool build checks - and each one
// says what the filter failed to notice.
//
// This is a MAINTAINER tool, not a player-facing one. Players vote;
// working out what the filter missed is our job, and it is the reason
// bad seeds are flagged in place rather than deleted. A pool that
// silently shrinks teaches nothing.
//
// Read it looking for a pattern rather than a single culprit. Several
// villages in a row would point at the blacksmith check; several
// desert temples at the missing wood check; a spread across types at
// something upstream of all of them.
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand } from '@aws-sdk/lib-dynamodb';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

async function main() {
	const table = process.argv[2];
	const asJson = process.argv.includes('--json');
	if (!table) {
		console.error('usage: npx tsx scripts/reviewBadSeeds.ts <seed-pool-table> [--json]');
		process.exit(1);
	}

	const rows: Record<string, any>[] = [];
	let startKey: Record<string, unknown> | undefined;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: table,
			FilterExpression: 'badSeed = :true',
			ExpressionAttributeValues: { ':true': true },
			ExclusiveStartKey: startKey,
		}));
		rows.push(...(page.Items ?? []));
		startKey = page.LastEvaluatedKey;
	} while (startKey);

	if (asJson) {
		console.log(JSON.stringify(rows, null, 2));
		return;
	}

	if (rows.length === 0) {
		console.log('No seeds have been voted bad.');
		return;
	}

	rows.sort((a, b) => (a.badSeedAt ?? 0) - (b.badSeedAt ?? 0));

	console.log(`${rows.length} quarantined seed${rows.length === 1 ? '' : 's'}\n`);
	for (const r of rows) {
		const when = r.badSeedAt ? new Date(r.badSeedAt).toISOString() : 'unknown';
		console.log(`  ${r.seedType ?? '?'}  overworld=${r.overworldSeed}  nether=${r.netherSeed}`);
		console.log(`    structure ${r.structureX},${r.structureZ}` +
			(r.smithX != null ? `  smith ${r.smithX},${r.smithZ}` : '') +
			`  bastion ${r.bastionType ?? '?'} ${r.bastionX},${r.bastionZ}`);
		console.log(`    voided ${when} by ${(r.badSeedVoters ?? []).length} players` +
			(r.badSeedReason ? `: ${r.badSeedReason}` : ''));
		console.log();
	}

	const byType: Record<string, number> = {};
	for (const r of rows) {
		const t = r.seedType ?? 'unknown';
		byType[t] = (byType[t] ?? 0) + 1;
	}
	console.log('by type:');
	for (const [t, n] of Object.entries(byType).sort((a, b) => b[1] - a[1])) {
		console.log(`  ${t.padEnd(16)} ${n}`);
	}
	console.log('\nA cluster in one type points at that type\'s own check.');
}

main().catch((err) => {
	console.error(err);
	process.exit(1);
});
