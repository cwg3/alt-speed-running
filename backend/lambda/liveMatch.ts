import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { paceFinishMs, revealedSplits, Schedule } from './lib/paceSchedule';
import { isSyntheticPlayer } from './lib/seedPool';
import { applyMatchCompletion, MatchPlayer } from './lib/matchCompletion';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const MATCH_HISTORY_TABLE_NAME = process.env.MATCH_HISTORY_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;

/**
 * Silence after which a player counts as gone.
 *
 * This poll IS the heartbeat - a client asking for live match state is
 * demonstrably still playing - so the check needs no scheduler and no
 * extra request: whoever is still there drives the resolution.
 *
 * Ten minutes is deliberately generous. A player who crashes and
 * relaunches Minecraft is back well inside it, and rejoining resumes
 * the same run rather than starting a new one, so the cost of waiting
 * is only that an abandoned match lingers a little. The cost of being
 * hasty is handing someone a loss while their game is still loading.
 */
const ABANDON_MS = 10 * 60 * 1000;

/**
 * Live match state for the in-game opponent-progress display.
 *
 * Labels the two players as "you"/"opponent" relative to the caller's
 * session rather than returning a raw uuid-keyed map - the client
 * already has to be authenticated for this, so the server may as well
 * do the perspective mapping instead of every caller reimplementing it.
 */
export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const callerUuid = await resolveSessionToken(SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!callerUuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	const matchId = event.pathParameters?.matchId;
	if (!matchId) {
		return { statusCode: 400, body: JSON.stringify({ error: 'matchId is required' }) };
	}

	const match = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId },
	}));
	if (!match.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}

	const players: MatchPlayer[] = match.Item.players;
	const you = players.find((p) => p.uuid === callerUuid);
	const opponent = players.find((p) => p.uuid !== callerUuid);
	if (!you || !opponent) {
		return { statusCode: 403, body: JSON.stringify({ error: 'you are not a participant in this match' }) };
	}

	// Record that this caller is alive, before anything else reads it.
	const now = Date.now();
	await ddb.send(new UpdateCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId },
		UpdateExpression: 'SET lastSeenAt.#uuid = :now',
		ConditionExpression: 'attribute_exists(lastSeenAt)',
		ExpressionAttributeNames: { '#uuid': callerUuid },
		ExpressionAttributeValues: { ':now': now },
	})).catch(() => {
		// Matches created before lastSeenAt existed have no map to
		// write into. They simply never resolve by abandonment, which
		// is the behaviour they had anyway.
	});

	// One player gone, the other still here: the one still here wins.
	//
	// Abandoning has to cost the match, or quitting becomes a way to
	// deny an opponent the win they are about to earn - a stalemate
	// on demand. A forfeit is the honest version of this and is one
	// click away.
	//
	// A PACE CANNOT ABANDON, and must never be judged to have. It has no
	// lastSeenAt because nothing polls for it, so the fallback below read
	// match.createdAt and would have handed the player a win by
	// abandonment within a couple of minutes of starting - mid-run, on a
	// race lasting a quarter of an hour. A pace is not absent; it is on a
	// schedule, and the block after this one is what ends its match.
	if (match.Item.status === 'pending' && match.Item.lastSeenAt
		&& !isSyntheticPlayer(opponent.uuid)) {
		const seen: Record<string, number> = match.Item.lastSeenAt;
		const opponentSeen = seen[opponent.uuid] ?? match.Item.createdAt ?? now;
		if (now - opponentSeen > ABANDON_MS) {
			console.log(`match ${matchId}: ${opponent.username} silent for ${now - opponentSeen}ms - awarding to ${you.username}`);
			await applyMatchCompletion(
				MATCHES_TABLE_NAME, PLAYERS_TABLE_NAME, matchId,
				you, opponent, match.Item.splits ?? {}, MATCH_HISTORY_TABLE_NAME);
			// Re-read so the response carries the result rather than
			// the pending state we fetched a moment ago.
			const after = await ddb.send(new GetCommand({
				TableName: MATCHES_TABLE_NAME,
				Key: { matchId },
			}));
			if (after.Item) {
				match.Item = after.Item;
			}
		}
	}

	// THE PACE REACHING THE FOUNTAIN ends the match.
	//
	// Something has to, or a paced match stays pending forever: the player
	// only calls /matches/complete when THEY finish, and a pace has no
	// process of its own to report anything. This poll is the natural
	// place, for the same reason the abandonment sweep lives here - it
	// rides a request the client already makes every few seconds.
	//
	// It scores nothing, because the opponent is synthetic and
	// matchCompletion withholds rating, season points and the W-L-F record
	// for an exhibition. What the player gets is an honest answer about
	// whether they beat the time.
	if (match.Item.status === 'pending' && isSyntheticPlayer(opponent.uuid)) {
		const sched = match.Item.paceSchedule as Schedule | undefined;
		const runStart = (match.Item.runStarts ?? {})[you.uuid];
		if (sched && typeof runStart === 'number' && runStart > 0
			&& now - runStart >= paceFinishMs(sched)) {
			console.log(`match ${matchId}: pace ${opponent.username} finished - ${you.username} did not beat it`);
			await applyMatchCompletion(
				MATCHES_TABLE_NAME, PLAYERS_TABLE_NAME, matchId,
				opponent, you, match.Item.splits ?? {}, MATCH_HISTORY_TABLE_NAME);
			const after = await ddb.send(new GetCommand({
				TableName: MATCHES_TABLE_NAME,
				Key: { matchId },
			}));
			if (after.Item) {
				match.Item = after.Item;
			}
		}
	}

	const splits: Record<string, Record<string, number>> = match.Item.splits ?? {};
	const results: Record<string, { ratingDelta: number; seasonPointsAwarded: number }> =
		match.Item.results ?? {};

	// Bad-seed votes, from the caller's perspective. The client shows a
	// prompt when the opponent has raised it and this player has not,
	// which is the only way the other side learns a vote is waiting -
	// there is no push channel, so it rides the poll that already runs.
	const badSeedVotes: Record<string, { at: number; reason: string }> =
		match.Item.badSeedVotes ?? {};
	const opponentBadSeed = badSeedVotes[opponent.uuid];

	// RACE A PACE: the opponent's splits are computed, not reported, and
	// this is where they are rationed.
	//
	// A pace's whole run is stored on the match row when the match is
	// created, which is what lets it exist without any process running for
	// the quarter of an hour the race takes. The cost of that shortcut is
	// that the run is knowable in advance, so it must never leave the
	// server whole: a player handed the schedule would know the result
	// before mining a block. revealedSplits keeps only what has already
	// come due, so the pace's progress arrives exactly as a human's would.
	//
	// Anchored to the PLAYER's run start, not to match creation. Loading
	// and world generation differ by machine and must come out of neither
	// side's clock - the same reason /matches/start exists. Before the
	// player's run begins the pace has not moved, which is correct: there
	// is nothing yet to be behind.
	let opponentSplits: Record<string, number> = splits[opponent.uuid] ?? {};
	let paceFinished = false;
	const paceSchedule = match.Item.paceSchedule as Schedule | undefined;
	if (paceSchedule && isSyntheticPlayer(opponent.uuid)) {
		const runStart = (match.Item.runStarts ?? {})[you.uuid];
		if (typeof runStart === 'number' && runStart > 0) {
			const elapsed = now - runStart;
			opponentSplits = revealedSplits(paceSchedule, elapsed) as Record<string, number>;
			paceFinished = elapsed >= paceFinishMs(paceSchedule);
		} else {
			// No run start yet, so no elapsed time exists to reveal
			// against. Empty, never the whole schedule.
			opponentSplits = {};
		}
	}

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			status: match.Item.status,
			winnerUuid: match.Item.winnerUuid ?? null,
			// WHY it was voided, not just that it was. Three different
			// things void a match - both players agreeing a seed is bad,
			// one player voiding a paced race, and the abandonment sweep -
			// and the client was announcing the first of them for all
			// three. It could not have done otherwise: this field existed
			// on the row and was never sent.
			voidReason: match.Item.voidReason ?? null,
			yourResult: results[you.uuid] ?? null,
			// Whether THIS caller's run has already begun. The client
			// uses it to skip the pre-race countdown on a rejoin: a
			// player twelve minutes into a run does not need ten
			// seconds to plan an opening they are long past, and
			// showing it costs them that time from a run in progress.
			yourRunStartedAt: (match.Item.runStarts ?? {})[you.uuid] ?? null,
			badSeed: {
				yours: Boolean(badSeedVotes[you.uuid]),
				opponent: Boolean(opponentBadSeed),
				opponentReason: opponentBadSeed?.reason ?? null,
			},
			you: {
				uuid: you.uuid,
				username: you.username,
				splits: splits[you.uuid] ?? {},
			},
			opponent: {
				uuid: opponent.uuid,
				username: opponent.username,
				splits: opponentSplits,
			},
			// Present only on a paced match, so a client that knows
			// nothing about paces behaves exactly as before.
			...(paceSchedule && isSyntheticPlayer(opponent.uuid)
				? {
					pace: {
						label: String(match.Item.paceLabel ?? ''),
						tier: String(match.Item.paceTier ?? ''),
						finished: paceFinished,
					},
				}
				: {}),
		}),
	};
};
