import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import {
	DeleteCommand, DynamoDBDocumentClient, GetCommand, PutCommand, ScanCommand, UpdateCommand,
} from '@aws-sdk/lib-dynamodb';
import { randomUUID } from 'crypto';
import { resolveSessionToken } from './lib/auth';
import { claimSeedPair, isSyntheticPlayer, recordSeedsSeen } from './lib/seedPool';
import { PACE_TIERS, paceFinishMs, scheduleForTier, tierById } from './lib/paceSchedule';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const QUEUE_TABLE_NAME = process.env.QUEUE_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const SEED_POOL_TABLE_NAME = process.env.SEED_POOL_TABLE_NAME!;

/**
 * The uuid the pace races under.
 *
 * It MUST be a synthetic player, because that is what makes the match an
 * exhibition in matchCompletion and therefore what stops a pace from
 * moving anybody's rating. Asserted at module load rather than trusted:
 * if this uuid and SYNTHETIC_PLAYERS ever drift apart, every paced match
 * silently becomes a rated one, which is a rating farm.
 */
const PACE_UUID = 'bot-rival';
if (!isSyntheticPlayer(PACE_UUID)) {
	throw new Error(`${PACE_UUID} is not a synthetic player - a paced match would be RATED`);
}

/**
 * Race a Pace: an opponent for when the queue is empty.
 *
 * An empty queue does not make the ladder slow, it makes it unplayable,
 * and somebody who finds it unplayable once does not come back to check
 * later. This is the answer to that, and SPEC's "Race a Pace" section is
 * the design.
 *
 * Three things this endpoint is responsible for getting right.
 *
 * IT IS NOT A RIVAL, AND IT SAYS SO. The opponent's stored username is
 * the pace's own label - "Pace 17:00" - so every surface that prints an
 * opponent name tells the truth without having to be taught about paces.
 * The alternative is a person-shaped name plus a disclaimer somewhere
 * else, which is how a player ends up believing they raced somebody.
 *
 * IT NEVER SCORES. The uuid above is synthetic, so matchCompletion
 * withholds Elo, season points and the W-L-F record. That guard exists
 * independently of this file and is asserted at load.
 *
 * IT SPENDS A SEED, LIKE ANY OTHER MATCH. The world is one the player has
 * never played, drawn by the same call ranked uses, and recorded as seen
 * BEFORE the match row is written. A player who loads it and quits has
 * still spent it, so it can never come back to them against somebody
 * seeing it cold.
 *
 * That cost is the point rather than a regret. This is a random-seed
 * ladder: a world they already knew would not practise the skill, and a
 * pace time set on an unknown world measures nothing when raced against
 * a memorised one.
 */
export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const json = (statusCode: number, body: unknown): APIGatewayProxyResultV2 => ({
		statusCode,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify(body),
	});

	const uuid = await resolveSessionToken(
		SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!uuid) {
		return json(401, { error: 'missing or invalid session token' });
	}

	let tierId = '';
	try {
		tierId = String(JSON.parse(event.body ?? '{}').tier ?? '');
	} catch {
		return json(400, { error: 'body must be JSON' });
	}
	const tier = tierById(tierId);
	if (!tier) {
		// Listed back, so a client that is out of date can show the real
		// options instead of failing with nothing to offer.
		return json(400, {
			error: 'unknown pace tier',
			tiers: PACE_TIERS.map((t) => ({ id: t.id, label: t.label })),
		});
	}

	const player = await ddb.send(new GetCommand({
		TableName: PLAYERS_TABLE_NAME,
		Key: { uuid },
	}));
	if (!player.Item) {
		return json(404, { error: 'no such player' });
	}
	if (player.Item.allowed === false) {
		return json(403, { error: 'not allowed to play' });
	}
	if (player.Item.currentMatchId) {
		// Not an error worth a 500. The client polls and may well have a
		// match already; telling it which one is more useful than a stack.
		return json(409, {
			error: 'already in a match',
			matchId: player.Item.currentMatchId,
		});
	}

	// A PACE IS THE FALLBACK, NEVER THE DEFAULT.
	//
	// If somebody else is queued, they are who this player should race. A
	// pace that is instantly available out-competes waiting a couple of
	// minutes for a human, and a ladder where everyone races paces has no
	// ladder - so the check lives on the server rather than trusting the
	// client to have tried first.
	//
	// Deliberately coarse: ANY other real player in the queue blocks it,
	// without reasoning about skill range or world-setup compatibility.
	// Refusing a pace to somebody who could not actually have been paired
	// costs them one more poll; handing out a pace while a human sits in
	// the queue costs the ladder a match.
	const queued = await ddb.send(new ScanCommand({
		TableName: QUEUE_TABLE_NAME,
		ProjectionExpression: '#u',
		ExpressionAttributeNames: { '#u': 'uuid' },
	}));
	const others = (queued.Items ?? [])
		.map((i) => String(i.uuid))
		.filter((u) => u !== uuid && !isSyntheticPlayer(u));
	if (others.length > 0) {
		return json(409, {
			error: 'someone is waiting to race',
			hint: 'keep polling /queue/join',
		});
	}

	const matchId = randomUUID();

	// A seed this player has never played, exactly as a ranked match gets.
	// A pace raced on a world they already know would not be practice for
	// this ladder - the skill here is reacting to an unknown world, and a
	// pace time set on one means nothing raced against a memorised one.
	const claim = await claimSeedPair(
		SEED_POOL_TABLE_NAME, matchId, PLAYERS_TABLE_NAME, [uuid]);
	if (!claim.ok) {
		return json(503, { error: 'no seed available', reason: claim.reason });
	}
	const pair = claim.pair;

	// Before the match exists, not after - the same order and the same
	// reason as the ranked path. If this fails the match is never created,
	// so a player cannot end up having played a seed that was never
	// recorded, which would hand it back to them later.
	try {
		await recordSeedsSeen(PLAYERS_TABLE_NAME, [uuid], pair.seedPairId);
	} catch (err) {
		console.error('[pacedMatch] could not record seen seed', err);
		return json(503, { error: 'could not reserve seed' });
	}

	const schedule = scheduleForTier(tier);
	const now = Date.now();
	const myRating = Number(player.Item.skillRating ?? 1500);

	await ddb.send(new PutCommand({
		TableName: MATCHES_TABLE_NAME,
		Item: {
			matchId,
			players: [
				{ uuid, username: player.Item.username, skillRating: myRating },
				// The label, not a name. See the header.
				{ uuid: PACE_UUID, username: `Pace ${tier.label}`, skillRating: myRating },
			],
			ratingDiff: 0,
			// Stored so a result screen never has to infer this from a
			// zero rating delta, which two evenly matched humans also
			// produce. matchCompletion writes it too; writing it at birth
			// means it is true for the whole life of the row.
			exhibition: true,
			paceTier: tier.id,
			paceLabel: tier.label,
			// The opponent's entire run, as data. Never served whole -
			// liveMatch reveals only what has already come due, so a
			// player cannot read the result before they start.
			paceSchedule: schedule,
			paceFinishMs: paceFinishMs(schedule),
			worldSetupVersion: Number(player.Item.worldSetupVersion ?? 1),
			seedPairId: pair.seedPairId,
			overworldSeed: pair.overworldSeed,
			netherSeed: pair.netherSeed,
			seedType: pair.seedType,
			structureX: pair.structureX,
			structureZ: pair.structureZ,
			bastionType: pair.bastionType,
			bastionX: pair.bastionX,
			bastionZ: pair.bastionZ,
			smithX: pair.smithX ?? null,
			smithZ: pair.smithZ ?? null,
			status: 'pending',
			createdAt: now,
			// Only the real player has an abandonment clock. A pace does
			// not poll and cannot go silent, and giving it a lastSeenAt
			// would start a clock that nothing ever advances - which the
			// sweep would eventually read as the pace having abandoned.
			lastSeenAt: { [uuid]: now },
			mods: Array.isArray(player.Item.mods) ? { [uuid]: player.Item.mods } : {},
			// The pace's clock is anchored to the PLAYER's run start, not
			// to match creation, so world generation and loading come out
			// of neither side's time. Filled by /matches/start.
			runStarts: {},
		},
	}));

	// The player may be sitting in the queue from an earlier poll. Leaving
	// the row would let them be matched into a second, real match while
	// already racing this one.
	await ddb.send(new DeleteCommand({ TableName: QUEUE_TABLE_NAME, Key: { uuid } }));

	await ddb.send(new UpdateCommand({
		TableName: PLAYERS_TABLE_NAME,
		Key: { uuid },
		UpdateExpression: 'SET currentMatchId = :m',
		ExpressionAttributeValues: { ':m': matchId },
	}));

	return json(200, {
		matched: true,
		matchId,
		// Same shape the ranked path returns, so the client builds the
		// world through one code path rather than two.
		opponent: { uuid: PACE_UUID, username: `Pace ${tier.label}` },
		exhibition: true,
		paceTier: tier.id,
		paceLabel: tier.label,
		worldSetupVersion: Number(player.Item.worldSetupVersion ?? 1),
		seedPairId: pair.seedPairId,
		overworldSeed: pair.overworldSeed,
		netherSeed: pair.netherSeed,
		seedType: pair.seedType,
		structureX: pair.structureX,
		structureZ: pair.structureZ,
		bastionType: pair.bastionType,
		bastionX: pair.bastionX,
		bastionZ: pair.bastionZ,
		smithX: pair.smithX ?? null,
		smithZ: pair.smithZ ?? null,
	});
};
