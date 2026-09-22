import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { ConditionalCheckFailedException } from '@aws-sdk/client-dynamodb';
import { randomInt } from 'node:crypto';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

export interface SeedPair {
	seedPairId: string;
	overworldSeed: number;
	netherSeed: number;
	/**
	 * Which opening route this seed is for: village, desert_temple,
	 * ruined_portal, shipwreck or buried_treasure.
	 *
	 * A seed is good for exactly one route, never all of them, so the
	 * client has to be told which - it drives what the mod guarantees
	 * when it builds the world (lava pools near a village, a usable
	 * portal at a ruined portal, and so on).
	 */
	seedType: string;
	/** The qualifying structure, so the mod knows where to place. */
	structureX: number;
	structureZ: number;
	/** Informational: which of the four bastions this seed gives. */
	bastionType: string | null;
	/** Where the bastion is, so the mod can top up its chests. */
	bastionX: number;
	bastionZ: number;
	/**
	 * Where the blacksmith is, on village seeds. Null on every other
	 * type, and on village rows loaded before this existed.
	 *
	 * structureX/Z is the village's jigsaw ANCHOR, which is not where
	 * the player needs to go. A village bounding box can be 107 by 174
	 * blocks, and one measured seed put the smith 54 blocks from the
	 * anchor - far enough that a player standing on the coordinate they
	 * were given reported no blacksmith at all. The smith is the
	 * objective on this seed type, so it is what gets shipped.
	 */
	smithX: number | null;
	smithZ: number | null;
}

// Atomically claims one unused seed pair for a match. Scans for
// candidates, then races a conditional update against each in turn -
// the condition (used = false) means a concurrent claim by another
// match loses cleanly instead of double-assigning the same seed.
// Fine at MVP scale; would need a smarter allocation strategy (or a
// bigger pool + random start point) once concurrent matches are common
// enough for repeated contention on the same few candidates to matter.
export async function claimSeedPair(
	seedPoolTableName: string,
	matchId: string,
): Promise<SeedPair | null> {
	// TEMPORARY: restrict which seed types are handed out.
	//
	// Set SEED_TYPE_BIAS to a comma-separated list (for example
	// "village,desert_temple") to draw only those. Exists so a player
	// can practise a specific opening, or so testing can reach the
	// nether reliably instead of spending every match hunting a magma
	// ravine. Unset it and the pool goes back to drawing evenly, which
	// is what a real ladder should do - an even mix is the whole point
	// of having five types.
	const bias = (process.env.SEED_TYPE_BIAS ?? '')
		.split(',')
		.map((t) => t.trim())
		.filter(Boolean);

	const names: Record<string, string> = { '#used': 'used' };
	const values: Record<string, unknown> = { ':false': false };
	let filter = '#used = :false';
	if (bias.length > 0) {
		names['#type'] = 'seedType';
		const placeholders = bias.map((t, i) => {
			values[`:t${i}`] = t;
			return `:t${i}`;
		});
		filter += ` AND #type IN (${placeholders.join(', ')})`;
	}

	// Collect candidates across pages, then draw at random.
	//
	// Scan returns rows in hash-key order, which is arbitrary but
	// STABLE, and the claim loop used to take the first one it saw with
	// a Limit of 25. Two consequences, both bad: only the first stretch
	// of the table was ever reachable, and releasing a seed put it back
	// in the same position so it came straight back out. A player
	// testing with repeated resets drew the same world eight times
	// running, and on a real ladder the rotation would be learnable -
	// knowing the next seed is knowing where every structure is.
	//
	// The whole pool is scanned instead. At MVP size that is one call
	// and a few hundred rows; CANDIDATE_CAP stops it growing unbounded,
	// and the shuffle means the cap does not reintroduce a fixed
	// window. If the pool ever reaches the point where scanning it per
	// match is the wrong shape, the replacement is a random partition
	// key to seek into - not a return to taking the first row.
	const CANDIDATE_CAP = 500;
	const items: Record<string, any>[] = [];
	let startKey: Record<string, any> | undefined;
	do {
		const page = await ddb.send(new ScanCommand({
			TableName: seedPoolTableName,
			FilterExpression: filter,
			ExpressionAttributeNames: names,
			ExpressionAttributeValues: values,
			ExclusiveStartKey: startKey,
		}));
		items.push(...(page.Items ?? []));
		startKey = page.LastEvaluatedKey;
	} while (startKey && items.length < CANDIDATE_CAP);

	// Fisher-Yates, from crypto rather than Math.random. Which seed
	// comes next is competitively meaningful - a predictable rotation
	// would let a player know the world before it is dealt - so the
	// draw should not be reconstructible from watching earlier ones.
	for (let i = items.length - 1; i > 0; i--) {
		const j = randomInt(i + 1);
		[items[i], items[j]] = [items[j], items[i]];
	}

	for (const item of items) {
		try {
			await ddb.send(new UpdateCommand({
				TableName: seedPoolTableName,
				Key: { seedPairId: item.seedPairId },
				UpdateExpression: 'SET #used = :true, assignedMatchId = :matchId',
				ConditionExpression: '#used = :false',
				ExpressionAttributeNames: { '#used': 'used' },
				ExpressionAttributeValues: { ':true': true, ':false': false, ':matchId': matchId },
			}));
			return {
				seedPairId: item.seedPairId,
				overworldSeed: item.overworldSeed,
				netherSeed: item.netherSeed,
				seedType: item.seedType ?? 'village',
				structureX: item.structureX ?? 0,
				structureZ: item.structureZ ?? 0,
				bastionType: item.bastionType ?? null,
				bastionX: item.bastionX ?? 0,
				bastionZ: item.bastionZ ?? 0,
				smithX: item.smithX ?? null,
				smithZ: item.smithZ ?? null,
			};
		} catch (err) {
			if (err instanceof ConditionalCheckFailedException) {
				continue; // someone else claimed it first - try the next candidate
			}
			throw err;
		}
	}

	return null;
}
