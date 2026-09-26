import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { applyMatchCompletion, MatchPlayer } from './lib/matchCompletion';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const MATCH_HISTORY_TABLE_NAME = process.env.MATCH_HISTORY_TABLE_NAME!;

interface CompleteRequest {
	matchId: string;
	winnerUuid: string;
	/** Run time at the fountain. Decides a close finish. */
	elapsedMs?: number;
}

/**
 * How long a finish stays provisional before it is settled.
 *
 * The race is won by the LOWEST RUN TIME, not by whichever packet
 * arrives first. Those usually agree, and on a close finish they do
 * not: two runners a few hundred milliseconds apart are separated by
 * less than the spread in their network latency, so first-to-report
 * would hand the win to the better connection.
 *
 * The alternative to waiting is completing immediately and reversing
 * it when a faster time turns up - which means unwinding Elo, season
 * points, a history row and a statistical review that have already
 * been applied to real accounts. Three seconds of "finishing..." is a
 * much smaller price than a rating system that sometimes runs
 * backwards.
 */
const PROVISIONAL_MS = 3_000;

// Explicit result reporting, and the ONLY path that completes a match.
// reportSplit records and awards nothing - the fountain ends the race, so
// a kill_dragon split is a PRECONDITION here rather than a trigger there.
//
// This comment claimed the opposite for a while, and it cost something:
// the two-player integration test posted a win with no splits behind it,
// read the resulting 409 as "the match will not complete", and reported
// six cascading failures that named nothing. A stale comment is not
// harmless when a test believes it.
//
// Known, deliberate limitation: trusts whichever participant reports,
// with no server-side verification of the actual race outcome. Real
// anti-cheat/result-verification is later work.
export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const reporterUuid = await resolveSessionToken(SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!reporterUuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	let body: CompleteRequest;
	try {
		body = JSON.parse(event.body ?? '{}');
	} catch {
		return { statusCode: 400, body: JSON.stringify({ error: 'invalid JSON body' }) };
	}
	if (!body.matchId || !body.winnerUuid) {
		return { statusCode: 400, body: JSON.stringify({ error: 'matchId and winnerUuid are required' }) };
	}

	const match = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
	}));
	if (!match.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}

	const players: MatchPlayer[] = match.Item.players;
	if (!players.some((p) => p.uuid === reporterUuid)) {
		return { statusCode: 403, body: JSON.stringify({ error: 'you are not a participant in this match' }) };
	}
	const winner = players.find((p) => p.uuid === body.winnerUuid);
	const loser = players.find((p) => p.uuid !== body.winnerUuid);
	if (!winner || !loser) {
		return { statusCode: 400, body: JSON.stringify({ error: "winnerUuid must be one of this match's two players" }) };
	}

	// A win claimed through this endpoint must be earned.
	//
	// This used to be the manual/administrative path only, trusting
	// whichever participant reported. It is now the NORMAL win path -
	// the client calls it when the player enters the exit fountain -
	// so it needs the check the split path already had. The winner
	// must have a kill_dragon split on this match, which reportSplit
	// only records after its ordering, floor and gap rules pass.
	//
	// selfReported is the distinction that matters: a player claiming
	// their OWN win has to have killed the dragon. Conceding to the
	// opponent needs no such proof, and forfeits run through their own
	// endpoint regardless.
	const splits: Record<string, Record<string, number>> = match.Item.splits ?? {};
	const selfReported = body.winnerUuid === reporterUuid;
	if (selfReported && splits[body.winnerUuid]?.kill_dragon === undefined) {
		return {
			statusCode: 409,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({
				error: 'no kill_dragon split recorded - the dragon must be dead before the fountain ends the race',
			}),
		};
	}

	// Lowest run time wins, so a finish is provisional first.
	//
	// The claim is parked on the match instead of completing it. A
	// faster time arriving inside the window replaces it; the window
	// then closes and whoever holds the claim wins. Nothing is applied
	// to a player's rating until that is settled, so nothing ever has
	// to be taken back.
	//
	// Only for a self-reported win. Conceding, and the administrative
	// path, settle immediately - there is no race to resolve.
	const myElapsed = Number.isFinite(body.elapsedMs) ? Number(body.elapsedMs) : null;
	if (selfReported && myElapsed !== null) {
		const claim = match.Item.finishClaim as
			{ uuid: string; elapsedMs: number; at: number } | undefined;

		if (!claim || myElapsed < claim.elapsedMs) {
			// Keep the first claim's clock. A later, faster finisher
			// must not restart the window and hold the result open.
			const at = claim ? claim.at : Date.now();
			try {
				await ddb.send(new UpdateCommand({
					TableName: MATCHES_TABLE_NAME,
					Key: { matchId: body.matchId },
					UpdateExpression: 'SET finishClaim = :c',
					ConditionExpression: claim
						? '#status = :pending AND finishClaim.elapsedMs = :was'
						: '#status = :pending AND attribute_not_exists(finishClaim)',
					ExpressionAttributeNames: { '#status': 'status' },
					ExpressionAttributeValues: claim
						? { ':c': { uuid: reporterUuid, elapsedMs: myElapsed, at },
							':pending': 'pending', ':was': claim.elapsedMs }
						: { ':c': { uuid: reporterUuid, elapsedMs: myElapsed, at },
							':pending': 'pending' },
				}));
			} catch (err) {
				if (!(err instanceof ConditionalCheckFailedException)) {
					throw err;
				}
				// Someone else moved first; fall through and re-read.
			}
		}

		const now = Date.now();
		const current = (await ddb.send(new GetCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId: body.matchId },
			ProjectionExpression: 'finishClaim',
		}))).Item?.finishClaim as { uuid: string; elapsedMs: number; at: number } | undefined;

		if (current && now - current.at < PROVISIONAL_MS) {
			return {
				statusCode: 200,
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({
					provisional: true,
					retryInMs: PROVISIONAL_MS - (now - current.at),
					leading: current.uuid === reporterUuid,
				}),
			};
		}
		// Window closed. The claim holder wins, which may not be the
		// caller - the slower finisher's own retry settles the match
		// for the faster one.
		if (current && current.uuid !== body.winnerUuid) {
			const realWinner = players.find((p) => p.uuid === current.uuid);
			const realLoser = players.find((p) => p.uuid !== current.uuid);
			if (realWinner && realLoser) {
				const settled = await applyMatchCompletion(
					MATCHES_TABLE_NAME, PLAYERS_TABLE_NAME, body.matchId,
					realWinner, realLoser, splits, MATCH_HISTORY_TABLE_NAME);
				return {
					statusCode: 200,
					headers: { 'content-type': 'application/json' },
					body: JSON.stringify(settled.alreadyCompleted
						? { alreadyCompleted: true }
						: { winner: settled.winner, loser: settled.loser }),
				};
			}
		}
	}

	const result = await applyMatchCompletion(
		MATCHES_TABLE_NAME, PLAYERS_TABLE_NAME, body.matchId, winner, loser,
		splits, MATCH_HISTORY_TABLE_NAME);

	if (result.alreadyCompleted) {
		return {
			statusCode: 200,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ alreadyCompleted: true }),
		};
	}

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({ winner: result.winner, loser: result.loser }),
	};
};
