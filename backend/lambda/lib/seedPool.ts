import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { BatchGetCommand, DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
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

/** Why a claim failed, so the caller can say which. */
export type ClaimFailure = 'pool_empty' | 'all_seen';

export type ClaimResult =
	| { ok: true; pair: SeedPair }
	| { ok: false; reason: ClaimFailure };

/**
 * Draws a seed neither player has played before.
 *
 * Seeds used to be CONSUMED: the claim set used = true and that pair
 * was gone for good, so the pool was a count of matches the ladder
 * could ever run. 300 seeds meant 300 matches, total, across everyone.
 *
 * Fairness never needed that. Both players race the SAME seed, so a
 * seed is only unfair when one of them has seen it and the other has
 * not. Tracking what each player has seen turns pool size from a
 * global budget into a per-player one: 300 seeds now means each player
 * can play 300 matches, and a hundred players get 15,000 matches out
 * of the same pool instead of 300.
 *
 * Every sighting counts, including a match abandoned after thirty
 * seconds. Strictly any sighting is information, and the alternative -
 * deciding how much of a seed someone saw before it counts - is a rule
 * that has to be defended later. Over-applying it is the safer error.
 *
 * `used` is left alone. It still gates rows that are held pending
 * verification or quarantined by a bad-seed vote, which is a different
 * question from whether a given player has seen a seed, and
 * verify-and-release.sh depends on it.
 */
export async function claimSeedPair(
	seedPoolTableName: string,
	matchId: string,
	playersTableName: string,
	playerUuids: string[],
): Promise<ClaimResult> {
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

	// What have these two already played?
	//
	// One BatchGet rather than a read per candidate. A player's set is
	// seed ids, so a thousand matches is tens of kilobytes - well
	// inside the 400KB item limit, but it wants a plan before it is
	// one.
	const seen = new Set<string>();
	if (playerUuids.length > 0) {
		const got = await ddb.send(new BatchGetCommand({
			RequestItems: {
				[playersTableName]: {
					Keys: playerUuids.map((uuid) => ({ uuid })),
					ProjectionExpression: 'seenSeeds',
				},
			},
		}));
		for (const row of got.Responses?.[playersTableName] ?? []) {
			// DocumentClient gives a Set for a DynamoDB string set.
			const s = row.seenSeeds;
			if (!s) continue;
			for (const id of (s instanceof Set ? Array.from(s) : s) as string[]) {
				seen.add(id);
			}
		}
	}

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

	// "No seeds at all" and "none this pair has not already played" are
	// different problems with different fixes - refill the pool, or
	// widen it for a heavy player - and they must not look alike to
	// the caller. Exhaustion here is per-player and would otherwise be
	// silent: everyone else keeps matching fine.
	if (items.length === 0) {
		return { ok: false, reason: 'pool_empty' };
	}
	const unseen = items.filter((i) => !seen.has(String(i.seedPairId)));
	if (unseen.length === 0) {
		return { ok: false, reason: 'all_seen' };
	}

	for (const item of unseen) {
		try {
			// No conditional claim any more. Two concurrent matches
			// drawing the same seed is now legal - it is only unfair if
			// a PLAYER repeats one - so the contention the old
			// used = false condition existed to resolve is gone.
			await ddb.send(new UpdateCommand({
				TableName: seedPoolTableName,
				Key: { seedPairId: item.seedPairId },
				UpdateExpression:
					'SET lastAssignedMatchId = :matchId ADD timesPlayed :one',
				ExpressionAttributeValues: { ':matchId': matchId, ':one': 1 },
			}));
			return { ok: true, pair: {
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
			} };
		} catch (err) {
			if (err instanceof ConditionalCheckFailedException) {
				continue; // someone else got there first - try the next
			}
			throw err;
		}
	}

	return { ok: false, reason: 'pool_empty' };
}

/**
 * Records that these players have now seen this seed.
 *
 * ADD on a string set is atomic and idempotent, so concurrent matches
 * cannot clobber each other and a retry is harmless.
 *
 * This must not be best-effort. If the match is created and this does
 * not land, the seed can be dealt to the same player again, which is
 * the exact thing the whole scheme exists to prevent - so the caller
 * treats a failure here as a failed match, not a warning.
 */
export async function recordSeedsSeen(
	playersTableName: string,
	playerUuids: string[],
	seedPairId: string,
): Promise<void> {
	await Promise.all(playerUuids.map((uuid) => ddb.send(new UpdateCommand({
		TableName: playersTableName,
		Key: { uuid },
		UpdateExpression: 'ADD seenSeeds :s',
		ExpressionAttributeValues: { ':s': new Set([seedPairId]) },
	}))));
}
