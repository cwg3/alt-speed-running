import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import {
	DeleteCommand,
	DynamoDBDocumentClient,
	GetCommand,
	PutCommand,
	ScanCommand,
	UpdateCommand,
} from '@aws-sdk/lib-dynamodb';
import { randomUUID } from 'crypto';
import { resolveSessionToken } from './lib/auth';
import { claimSeedPair, recordSeedsSeen } from './lib/seedPool';
import { illegalMods } from './lib/modRules';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const QUEUE_TABLE_NAME = process.env.QUEUE_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const SEED_POOL_TABLE_NAME = process.env.SEED_POOL_TABLE_NAME!;

// Skill-range matchmaking: our own documented policy (not a copy of any
// other platform's undisclosed formula). Range widens the longer a
// waiting candidate has been queued, capped at MAX_RANGE (effectively
// open matchmaking after ~45s of waiting) - uses the CANDIDATE's wait
// time specifically, since the point is "how much should we relax
// match quality for someone who's already been waiting a while", not
// the freshly-joining player's (whose wait is always ~0).
const BASE_RATING_RANGE = 50;

// A queue row is only a real waiting player for as long as that client
// keeps polling. The client polls every 3s while searching, so a row
// untouched for this long belongs to someone who quit, crashed, or
// closed the game.
//
// Without this check a dead row waits forever, and because the range
// widening below is driven by wait time, a dead row's allowed range
// climbs to the cap - so the longer a ghost sat in the queue the more
// aggressively it got matched against real players. A stranger who
// closed the client mid-search would keep being paired into matches
// they never joined, stranding whoever matched them.
/**
 * Whether a mod outside the whitelist refuses a queue join.
 *
 * DEFAULTS TO OFF, which is the opposite of the allowlist's default and
 * deliberately so. The whitelist was written from first principles and
 * one runner's judgement; step 1 exists partly to find out what people
 * actually run, and no recorded list has been looked at yet. Turning
 * this on first would refuse honest players over a list nobody has
 * checked against reality - and MCSR Fairplay's id is still missing from
 * it, so today it would refuse a mod the README calls legal.
 *
 * Same instinct as the split rules: absent or unproven data degrades to
 * loose, never to strict. Set MOD_GATE_ENABLED=true once the recorded
 * lists say the whitelist matches what real clients carry.
 */
const MOD_GATE_ENABLED = (process.env.MOD_GATE_ENABLED ?? 'false') === 'true';

const QUEUE_STALE_MS = 30_000;
const RANGE_GROWTH_PER_SECOND = 10;
const MAX_RATING_RANGE = 500;

export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const uuid = await resolveSessionToken(SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!uuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	const player = await ddb.send(new GetCommand({
		TableName: PLAYERS_TABLE_NAME,
		Key: { uuid },
	}));
	if (!player.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'no player record for this session' }) };
	}
	const myRating: number = player.Item.skillRating;

	// What this player has loaded, recorded at login. undefined when the
	// client never reported one, which stays distinct from an empty list
	// all the way to the detail screen.
	const myMods: string[] | undefined = Array.isArray(player.Item.mods)
		? player.Item.mods
		: undefined;

	// A player whose opponent created the match must still be told about
	// it. Only the caller that finds an opponent gets matched:true, so
	// without this the other side sees an empty queue, re-queues itself
	// and waits forever while its match sits pending.
	//
	// KEEP THIS RESPONSE IN STEP WITH THE ONE AT THE END OF THE HANDLER.
	// Every seed field has to appear in three places - the match row,
	// the creator's response, and this one - and smithX/smithZ was once
	// added to the first two only. The result was not a missing log
	// line: whichever player created the match got the blacksmith's
	// position and the other did not, on the same seed, which is
	// exactly the asymmetry this project exists to remove.
	const existingMatchId: string | undefined = player.Item.currentMatchId;
	if (existingMatchId) {
		const existing = await ddb.send(new GetCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId: existingMatchId },
		}));
		if (existing.Item && existing.Item.status === 'pending') {
			const them = (existing.Item.players as any[]).find((p) => p.uuid !== uuid);
			// Drop any stale queue row so this player isn't matched twice.
			await ddb.send(new DeleteCommand({ TableName: QUEUE_TABLE_NAME, Key: { uuid } }));
			return {
				statusCode: 200,
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({
					matched: true,
					matchId: existingMatchId,
					opponent: { uuid: them.uuid, username: them.username },
					overworldSeed: existing.Item.overworldSeed,
					netherSeed: existing.Item.netherSeed,
					seedType: existing.Item.seedType ?? 'village',
					structureX: existing.Item.structureX ?? 0,
					structureZ: existing.Item.structureZ ?? 0,
					bastionType: existing.Item.bastionType ?? null,
					bastionX: existing.Item.bastionX ?? 0,
					bastionZ: existing.Item.bastionZ ?? 0,
					smithX: existing.Item.smithX ?? null,
					smithZ: existing.Item.smithZ ?? null,
					// Rejoining a match whose run has already begun. The
					// client uses this to skip the ten-second seed-reveal
					// countdown: it is planning time before a race, and
					// this player's race is already running. Null when the
					// match exists but they never got as far as starting -
					// quitting during the countdown itself - and they do
					// still get their planning time.
					yourRunStartedAt: (existing.Item.runStarts ?? {})[uuid] ?? null,
				}),
			};
		}
	}

	// The mod rule, enforced at the DOOR.
	//
	// AFTER the rejoin path above, and that order is the whole design. A
	// player already in a pending match is mid-run: refusing them here
	// would strand a match in progress, and taking away a run somebody
	// is already in - over a mod they did not know was illegal - is the
	// grievance this ladder exists to answer. Never at /matches/complete
	// either. Turned away before a match exists, or not at all.
	//
	// Each player is checked on their own join, so both sides of a pair
	// have passed by the time they can be paired. The opponent's row is
	// not re-checked here: it was checked when they queued, and a rule
	// change mid-queue is not worth a second read per candidate.
	if (MOD_GATE_ENABLED) {
		const illegal = illegalMods(myMods);
		if (illegal.length > 0) {
			// Names the mods. "You are not allowed to queue" with no
			// subject is the kind of refusal this project objects to
			// when other people do it.
			console.log(`[queueJoin] ${uuid} refused: ${illegal.join(' ')}`);
			return {
				statusCode: 403,
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({
					error: 'not on the mod whitelist: ' + illegal.join(', ')
						+ ' - see the mod list in README, or open an issue to argue for it',
					reason: 'mod_not_allowed',
					mods: illegal,
				}),
			};
		}
	}

	// Full table Scan is a known simplification, fine at test scale - see
	// the seed pool / matches tables for the same reasoning elsewhere in
	// this backend.
	const waiting = await ddb.send(new ScanCommand({ TableName: QUEUE_TABLE_NAME }));
	const now = Date.now();

	// Two players build their OWN world from the shared seed, so if
	// their world-building rules differ they are not racing the same
	// world. The client version gate only enforces a minimum, which
	// would happily pair 0.1.0 against a later build whose lava pools
	// land somewhere else. This is the check that makes the race fair,
	// and the same stamp is what lets a replay regenerate the world
	// later.
	const myWorldSetup: number = player.Item.worldSetupVersion ?? 0;

	// myMods is carried onto the queue row below, so pairing never needs
	// a second read per candidate - the same reason worldSetupVersion
	// lives there.

	let bestOpponent: any = null;
	let bestDiff = Infinity;
	let skippedForVersion = 0;
	// Whether this player already has a row, and whether it is still
	// live. A row left over from a previous session must not carry its
	// old joinedAt forward - see the update below.
	let myRowIsLive = false;
	for (const candidate of waiting.Items ?? []) {
		if (candidate.uuid === uuid) {
			const mineLastSeen = candidate.lastSeenAt ?? candidate.joinedAt;
			myRowIsLive = now - mineLastSeen <= QUEUE_STALE_MS;
			continue; // can't match with yourself
		}
		// lastSeenAt is absent on rows written before it existed; fall
		// back to joinedAt so those age out rather than living forever.
		const lastSeen = candidate.lastSeenAt ?? candidate.joinedAt;
		if (now - lastSeen > QUEUE_STALE_MS) {
			continue; // client stopped polling - not actually waiting
		}

		const waitSeconds = (now - candidate.joinedAt) / 1000;
		const allowedRange = Math.min(
			MAX_RATING_RANGE,
			BASE_RATING_RANGE + waitSeconds * RANGE_GROWTH_PER_SECOND,
		);
		if ((candidate.worldSetupVersion ?? 0) !== myWorldSetup) {
			skippedForVersion++;
			continue; // different world-building rules: different world
		}

		const diff = Math.abs(candidate.skillRating - myRating);
		if (diff <= allowedRange && diff < bestDiff) {
			bestOpponent = candidate;
			bestDiff = diff;
		}
	}

	if (!bestOpponent && skippedForVersion > 0) {
		// Worth saying out loud. To the player this looks like an empty
		// queue while somebody is plainly waiting, and the cause - one
		// of them has not updated - is invisible from the client.
		console.log(`[queueJoin] ${uuid} found no opponent; skipped `
			+ `${skippedForVersion} on worldSetupVersion (mine: ${myWorldSetup})`);
	}

	if (!bestOpponent) {
		// While a player is actively waiting, joinedAt must stay put or
		// the range-widening above would never widen for them.
		//
		// But a row left behind by a previous session must not keep its
		// old joinedAt. Someone who queued yesterday, quit, and came
		// back would otherwise be credited with a day of waiting, which
		// pins their allowed range at the cap - so a returning player
		// gets paired with anyone at up to MAX_RATING_RANGE difference
		// on their very first poll. That is exactly the mismatch the
		// widening schedule exists to ramp into gradually.
		const joinedAtClause = myRowIsLive
			? 'joinedAt = if_not_exists(joinedAt, :now)'
			: 'joinedAt = :now';
		await ddb.send(new UpdateCommand({
			TableName: QUEUE_TABLE_NAME,
			Key: { uuid },
			// lastSeenAt refreshes on every poll - that split between it
			// and joinedAt is what separates "waiting" from "gone".
			UpdateExpression: 'SET username = :username, skillRating = :rating, '
				+ joinedAtClause + ', '
				+ 'worldSetupVersion = :wsv, '
				+ (myMods ? 'mods = :mods, ' : '')
				+ 'lastSeenAt = :now, expiresAt = :expires',
			ExpressionAttributeValues: {
				':username': player.Item.username,
				':rating': myRating,
				// On the queue row so matching can compare it without a
				// second read per candidate.
				':wsv': myWorldSetup,
				...(myMods ? { ':mods': myMods } : {}),
				':now': now,
				// DynamoDB TTL (seconds) so abandoned rows are reaped
				// rather than accumulating forever. Correctness comes
				// from the staleness check above; this is housekeeping.
				':expires': Math.floor(now / 1000) + 3600,
			},
		}));
		return {
			statusCode: 200,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ matched: false }),
		};
	}

	const matchId = randomUUID();

	// Claim the seed before persisting the match - if the pool is
	// exhausted, fail loudly rather than create a match nobody can
	// actually play.
	const uuids = [uuid, bestOpponent.uuid];
	const claim = await claimSeedPair(SEED_POOL_TABLE_NAME, matchId, PLAYERS_TABLE_NAME, uuids);
	if (!claim.ok) {
		// Two different problems. 'pool_empty' is everyone's - refill
		// it. 'all_seen' is this PAIR's: the pool is fine and other
		// players are matching normally, but between them these two
		// have played everything in it. Reporting them the same way
		// would send someone hunting a pool that is not the problem.
		const error = claim.reason === 'all_seen'
			? 'no unplayed seed for these players - the pool needs more, or one of them has played it out'
			: 'no seed pairs available';
		return {
			statusCode: 503,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ error, reason: claim.reason }),
		};
	}
	const seedPair = claim.pair;

	// Before the match exists, not after. If this fails the match is
	// never created, so a player cannot end up having seen a seed that
	// was never recorded - which would hand it back to them later.
	try {
		await recordSeedsSeen(PLAYERS_TABLE_NAME, uuids, seedPair.seedPairId);
	} catch (err) {
		console.error('[queueJoin] could not record seen seeds', err);
		return {
			statusCode: 503,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ error: 'could not reserve seed' }),
		};
	}

	await ddb.send(new PutCommand({
		TableName: MATCHES_TABLE_NAME,
		Item: {
			matchId,
			players: [
				{ uuid, username: player.Item.username, skillRating: myRating },
				{ uuid: bestOpponent.uuid, username: bestOpponent.username, skillRating: bestOpponent.skillRating },
			],
			ratingDiff: bestDiff,
			// Which POOL ROW this came from, not just the numbers. A
			// match used to record only the seeds, so "which players
			// have seen this pair" could not be answered from history
			// without mapping seeds back to rows.
			// Which rules built this world. A replay regenerates the
			// world rather than storing it, so without this an old
			// match would be rebuilt under whatever rules are current -
			// a truthful movement trace inside a world the players
			// never saw.
			worldSetupVersion: myWorldSetup,
			seedPairId: seedPair.seedPairId,
			overworldSeed: seedPair.overworldSeed,
			netherSeed: seedPair.netherSeed,
			seedType: seedPair.seedType,
			structureX: seedPair.structureX,
			structureZ: seedPair.structureZ,
			bastionType: seedPair.bastionType,
			bastionX: seedPair.bastionX,
			bastionZ: seedPair.bastionZ,
			// Village seeds only. DynamoDB rejects undefined, so these
			// are written as null rather than omitted conditionally.
			smithX: seedPair.smithX ?? null,
			smithZ: seedPair.smithZ ?? null,
			status: 'pending',
			createdAt: now,
			// Both players are demonstrably present at the moment the
			// match is made, so the abandonment clock starts here
			// rather than from nothing - otherwise a match whose
			// opponent never polls would look abandoned from birth.
			lastSeenAt: { [uuid]: now, [bestOpponent.uuid]: now },
			// What each player had loaded when they logged in, uuid-keyed
			// like lastSeenAt and runStarts. A uuid MISSING from this map
			// reported no list, which the detail screen shows as "not
			// recorded" rather than as "none" - the difference is the
			// difference between no evidence and exculpatory evidence.
			mods: {
				...(myMods ? { [uuid]: myMods } : {}),
				...(Array.isArray(bestOpponent.mods)
					? { [bestOpponent.uuid]: bestOpponent.mods }
					: {}),
			},
			// Per-player run starts, filled in by /matches/start at each
			// player's first playable tick. Present but empty so that
			// claim is a single conditional write rather than a
			// create-the-map dance; createdAt is deliberately NOT used
			// as a run start, because loading time differs by machine.
			runStarts: {},
		},
	}));
	// Delete both sides - the caller may already have a row from an
	// earlier unmatched poll (the normal case for anyone who polls more
	// than once). DeleteItem on a nonexistent key is a harmless no-op, so
	// this is safe even for a caller matching on their very first call.
	await ddb.send(new DeleteCommand({ TableName: QUEUE_TABLE_NAME, Key: { uuid: bestOpponent.uuid } }));
	await ddb.send(new DeleteCommand({ TableName: QUEUE_TABLE_NAME, Key: { uuid } }));

	// Record the match on both players so the opponent - who never sees
	// this response - can discover it on their next poll.
	for (const participant of [uuid, bestOpponent.uuid]) {
		await ddb.send(new UpdateCommand({
			TableName: PLAYERS_TABLE_NAME,
			Key: { uuid: participant },
			UpdateExpression: 'SET currentMatchId = :m',
			ExpressionAttributeValues: { ':m': matchId },
		}));
	}

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			matched: true,
			matchId,
			opponent: { uuid: bestOpponent.uuid, username: bestOpponent.username },
			// Which POOL ROW this came from, not just the numbers. A
			// match used to record only the seeds, so "which players
			// have seen this pair" could not be answered from history
			// without mapping seeds back to rows.
			// Which rules built this world. A replay regenerates the
			// world rather than storing it, so without this an old
			// match would be rebuilt under whatever rules are current -
			// a truthful movement trace inside a world the players
			// never saw.
			worldSetupVersion: myWorldSetup,
			seedPairId: seedPair.seedPairId,
			overworldSeed: seedPair.overworldSeed,
			netherSeed: seedPair.netherSeed,
			seedType: seedPair.seedType,
			structureX: seedPair.structureX,
			structureZ: seedPair.structureZ,
			bastionType: seedPair.bastionType,
			bastionX: seedPair.bastionX,
			bastionZ: seedPair.bastionZ,
			smithX: seedPair.smithX ?? null,
			smithZ: seedPair.smithZ ?? null,
		}),
	};
};
