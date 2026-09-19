// One-off loader: reads the Phase 1 cubiomes output
// (seed-filter/output/match_seeds.json) and populates the SeedPool
// table. Run with: npx tsx scripts/loadSeedPool.ts <table-name>
import * as fs from 'fs';
import * as path from 'path';
import { randomUUID } from 'crypto';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { BatchWriteCommand, DynamoDBDocumentClient } from '@aws-sdk/lib-dynamodb';

interface MatchSeed {
	overworldSeed: number;
	netherSeed: number;
}

async function main() {
	const tableName = process.argv[2];
	if (!tableName) {
		console.error('usage: npx tsx scripts/loadSeedPool.ts <seed-pool-table-name>');
		process.exit(1);
	}

	const seedsPath = path.join(__dirname, '..', '..', 'seed-filter', 'output', 'match_seeds.json');
	const seeds: MatchSeed[] = JSON.parse(fs.readFileSync(seedsPath, 'utf-8'));
	console.log(`Loaded ${seeds.length} seed pairs from ${seedsPath}`);

	const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

	// BatchWriteItem caps at 25 items per call.
	for (let i = 0; i < seeds.length; i += 25) {
		const chunk = seeds.slice(i, i + 25);
		await ddb.send(new BatchWriteCommand({
			RequestItems: {
				[tableName]: chunk.map((seed) => ({
					PutRequest: {
						Item: {
							seedPairId: randomUUID(),
							overworldSeed: seed.overworldSeed,
							netherSeed: seed.netherSeed,
							used: false,
						},
					},
				})),
			},
		}));
		console.log(`Wrote ${Math.min(i + 25, seeds.length)}/${seeds.length}`);
	}

	console.log('Done.');
}

main().catch((err) => {
	console.error(err);
	process.exit(1);
});
