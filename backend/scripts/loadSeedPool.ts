// Loads the cubiomes output into the SeedPool table.
//
//   npx tsx scripts/loadSeedPool.ts <table-name> [--replace] [--only=type] [--skip=type,type]
//
// Reads seed-filter/output/overworld_by_type.json (five pools, one per
// opening route) and nether_seeds.json, and pairs them 1:1. The two
// halves are searched independently and joined here, which deliberately
// breaks the correlation vanilla has between a seed's overworld and
// nether - that correlation is what makes "Divine Travel" style
// inference possible, and the established filters break it for the same
// reason.
//
// Each row carries the seed type and the qualifying structure's
// position, because the mod needs both at world creation: what it
// guarantees depends on the route (lava pools near a village, a usable
// portal at a ruined portal), and it has to know where to put things.
//
// --skip drops a type from the load. Ruined portal needs it until
// placement lands: cubiomes cannot predict whether a portal actually
// generates (its own comments say so), and measurement put only ~35% of
// otherwise-qualifying RP seeds as usable - the rest have no portal or
// one underground. Loading those would hand players unplayable matches.
//
// --only rebuilds exactly one type: it deletes that type's rows and
// loads only that type, leaving the rest of the pool untouched. Use it
// when one type's filter changes - rebuilding everything would mean
// re-running the ocean types' magma-ravine verification, which is hours
// of world generation.
//
// --replace clears the existing pool first. Without it the new seeds
// are added alongside whatever is already there, which is what you want
// when topping a pool up and emphatically not what you want after
// changing the filter criteria.
import * as fs from 'fs';
import * as path from 'path';
import { randomUUID } from 'crypto';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { BatchWriteCommand, DynamoDBDocumentClient, ScanCommand } from '@aws-sdk/lib-dynamodb';

interface OverworldSeed {
	seed: number;
	spawn: { x: number; z: number };
	structure: { x: number; z: number; distFromSpawn: number };
}

interface NetherSeed {
	seed: number;
	bastion: { x: number; z: number; dist: number; type: string };
	fortress: { x: number; z: number; distFromBastion: number };
}

const SEED_TYPES = [
	'village',
	'desert_temple',
	'ruined_portal',
	'shipwreck',
	'buried_treasure',
] as const;

/**
 * Deletes pool rows, optionally only those of one seed type.
 *
 * The type filter exists so one type can be rebuilt without discarding
 * the others. Rebuilding everything means re-running every
 * verification stage, and the ocean types' magma-ravine check is hours
 * of world generation - far too much to pay for a fix that only
 * concerns villages.
 */
async function clearPool(ddb: DynamoDBDocumentClient, tableName: string, onlyType?: string) {
	let cleared = 0;
	// Paginate on LastEvaluatedKey, NOT on an empty page. Scan applies
	// Limit BEFORE FilterExpression, so a filtered scan routinely
	// returns a page with no matches while rows of that type still lie
	// further on. Breaking on an empty page would leave some of them
	// behind, and the load would then append duplicates alongside.
	let startKey: Record<string, unknown> | undefined;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: tableName,
			ProjectionExpression: 'seedPairId, seedType',
			ExclusiveStartKey: startKey,
			...(onlyType
				? {
					FilterExpression: '#t = :t',
					ExpressionAttributeNames: { '#t': 'seedType' },
					ExpressionAttributeValues: { ':t': onlyType },
				}
				: {}),
		}));
		const items = page.Items ?? [];
		// BatchWrite caps at 25 requests.
		for (let i = 0; i < items.length; i += 25) {
			const chunk = items.slice(i, i + 25);
			await ddb.send(new BatchWriteCommand({
				RequestItems: {
					[tableName]: chunk.map((item) => ({
						DeleteRequest: { Key: { seedPairId: item.seedPairId } },
					})),
				},
			}));
			cleared += chunk.length;
		}
		startKey = page.LastEvaluatedKey;
	} while (startKey);
	console.log(`Cleared ${cleared} existing rows${onlyType ? ` of type ${onlyType}` : ''}`);
}

async function main() {
	const tableName = process.argv[2];
	const replace = process.argv.includes('--replace');
	const skipArg = process.argv.find((a) => a.startsWith('--skip='));
	const skip = new Set(skipArg ? skipArg.slice('--skip='.length).split(',') : []);
	// Rebuild exactly one seed type, leaving the rest of the pool
	// alone: deletes that type's rows and loads only that type.
	const onlyArg = process.argv.find((a) => a.startsWith('--only='));
	const only = onlyArg ? onlyArg.slice('--only='.length) : undefined;
	if (!tableName) {
		console.error('usage: npx tsx scripts/loadSeedPool.ts <table> [--replace] [--only=<type>] [--skip=a,b]');
		process.exit(1);
	}
	if (only && !SEED_TYPES.includes(only as typeof SEED_TYPES[number])) {
		console.error(`--only must be one of: ${SEED_TYPES.join(', ')}`);
		process.exit(1);
	}
	if (only && replace) {
		console.error('--only and --replace are mutually exclusive: --replace wipes every type');
		process.exit(1);
	}

	const outputDir = path.join(__dirname, '..', '..', 'seed-filter', 'output');
	const byType: Record<string, OverworldSeed[]> = JSON.parse(
		fs.readFileSync(path.join(outputDir, 'overworld_by_type.json'), 'utf-8'));
	const nether: NetherSeed[] = JSON.parse(
		fs.readFileSync(path.join(outputDir, 'nether_seeds.json'), 'utf-8'));

	// Blacksmith positions, keyed by overworld seed, from the pool
	// build's smith stage (mod/run/smith.csv:
	// seed,hasSmith,spanX,spanZ,smithX,smithZ).
	//
	// Village seeds ship this because structureX/Z is the jigsaw
	// ANCHOR, not the building the player is being sent to - a village
	// box can be 107 by 174 blocks, and a player standing on the anchor
	// reported no blacksmith at all when it was 54 blocks away.
	//
	// Optional: if the file is absent the rows simply carry null, which
	// is the same as every row loaded before this existed.
	const smithPos = new Map<string, { x: number; z: number }>();
	const smithCsv = path.join(__dirname, '..', '..', 'mod', 'run', 'smith.csv');
	if (fs.existsSync(smithCsv)) {
		for (const line of fs.readFileSync(smithCsv, 'utf-8').split('\n')) {
			const parts = line.trim().split(',');
			if (parts.length >= 6 && parts[1] === 'true' && parts[4] !== '' && parts[5] !== '') {
				smithPos.set(parts[0], { x: Number(parts[4]), z: Number(parts[5]) });
			}
		}
		console.log(`Loaded ${smithPos.size} blacksmith positions`);
	} else {
		console.warn('No mod/run/smith.csv - village rows will carry no smith position');
	}

	const rows: Record<string, unknown>[] = [];
	let netherIndex = 0;
	for (const seedType of SEED_TYPES) {
		if (only && seedType !== only) {
			continue;
		}
		if (skip.has(seedType)) {
			console.log(`Skipping ${seedType}`);
			continue;
		}
		const overworld = byType[seedType] ?? [];
		for (const ow of overworld) {
			if (netherIndex >= nether.length) {
				console.warn(`Ran out of nether seeds after ${rows.length} pairs`);
				break;
			}
			const nh = nether[netherIndex++];
			rows.push({
				seedPairId: randomUUID(),
				overworldSeed: ow.seed,
				netherSeed: nh.seed,
				seedType,
				structureX: ow.structure.x,
				structureZ: ow.structure.z,
				spawnX: ow.spawn.x,
				spawnZ: ow.spawn.z,
				bastionType: nh.bastion.type,
				// The mod tops up the bastion's chests at world
				// creation and needs to know where it is.
				bastionX: nh.bastion.x,
				bastionZ: nh.bastion.z,
				smithX: smithPos.get(String(ow.seed))?.x ?? null,
				smithZ: smithPos.get(String(ow.seed))?.z ?? null,
				used: false,
			});
		}
	}

	const counts: Record<string, number> = {};
	for (const row of rows) {
		const t = row.seedType as string;
		counts[t] = (counts[t] ?? 0) + 1;
	}
	console.log(`Prepared ${rows.length} seed pairs:`, counts);

	const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
	if (only) {
		await clearPool(ddb, tableName, only);
	}
	if (replace) {
		await clearPool(ddb, tableName);
	}

	// BatchWriteItem caps at 25 items per call.
	for (let i = 0; i < rows.length; i += 25) {
		await ddb.send(new BatchWriteCommand({
			RequestItems: {
				[tableName]: rows.slice(i, i + 25).map((Item) => ({ PutRequest: { Item } })),
			},
		}));
		console.log(`Wrote ${Math.min(i + 25, rows.length)}/${rows.length}`);
	}

	console.log('Done.');
}

main().catch((err) => {
	console.error(err);
	process.exit(1);
});
