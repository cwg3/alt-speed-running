import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { MatchPlayer } from './lib/matchCompletion';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;

/**
 * Claims the moment a player's run actually began. Mints once, ever.
 *
 * The run timer has to satisfy two things that pull against each other.
 *
 * FAIRNESS. It must not start when the match is created, because world
 * generation and loading come out of the player's clock and cost
 * different amounts on different hardware. Two players racing the same
 * seed would lose different amounts of run time to their machines, and
 * the faster one gains ground that has nothing to do with skill. So the
 * timestamp is taken at the player's own first playable tick.
 *
 * INTEGRITY. It must not be something the client can mint again. The
 * client used to set its own start time on that first playable tick and
 * tell nobody, which meant quitting to the title screen and rejoining
 * the still-pending match handed you a fresh 0:00 on a seed whose
 * structures you had already found. Unlimited free resets.
 *
 * Those are reconcilable: let the client choose the MOMENT, but let the
 * server own the RECORD. The conditional write below is the whole
 * mechanism - the first call for a given player and match stores a
 * timestamp, and every call after that fails the condition and reads
 * back the original. A player who crashes and rejoins resumes the run
 * they were already on, which is also the honest behaviour for the
 * case this was reported from: a crash is not a reset.
 *
 * Note that this does NOT reject a slow first call. A player whose game
 * takes longer to load claims their start later and keeps the fairness
 * property. What they cannot do is claim it twice.
 *
 * The response carries serverNow so the caller can derive elapsed time
 * from a server-side difference rather than trusting two machines to
 * agree on the wall clock. A client whose clock is skewed by a minute
 * would otherwise show a timer wrong by a minute.
 */
export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const uuid = await resolveSessionToken(SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!uuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	let body: { matchId?: string };
	try {
		body = JSON.parse(event.body ?? '{}');
	} catch {
		return { statusCode: 400, body: JSON.stringify({ error: 'invalid JSON body' }) };
	}
	if (!body.matchId) {
		return { statusCode: 400, body: JSON.stringify({ error: 'matchId is required' }) };
	}
	const matchId = body.matchId;

	const match = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId },
	}));
	if (!match.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}

	const players: MatchPlayer[] = match.Item.players ?? [];
	if (!players.some((p) => p.uuid === uuid)) {
		return { statusCode: 403, body: JSON.stringify({ error: 'you are not a participant in this match' }) };
	}
	if (match.Item.status !== 'pending') {
		return { statusCode: 409, body: JSON.stringify({ error: 'match is already complete' }) };
	}

	const now = Date.now();
	let runStartedAt: number;
	let claimed: boolean;

	try {
		// attribute_not_exists on the player's own slot is what makes
		// this mint-once. Two clients racing, or one client retrying,
		// both end up with whichever write landed first.
		const written = await ddb.send(new UpdateCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId },
			UpdateExpression: 'SET runStarts.#uuid = :now',
			ConditionExpression:
				'attribute_exists(runStarts) AND attribute_not_exists(runStarts.#uuid)',
			ExpressionAttributeNames: { '#uuid': uuid },
			ExpressionAttributeValues: { ':now': now },
			ReturnValues: 'ALL_NEW',
		}));
		runStartedAt = written.Attributes!.runStarts[uuid];
		claimed = true;
	} catch (err) {
		const name = (err as { name?: string }).name;
		if (name !== 'ConditionalCheckFailedException') {
			throw err;
		}
		// Either the map is missing entirely (a match created before
		// this endpoint existed) or this player already has a start.
		// Re-read to tell those apart rather than assuming.
		const fresh = await ddb.send(new GetCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId },
		}));
		const existing: number | undefined = fresh.Item?.runStarts?.[uuid];
		if (existing !== undefined) {
			runStartedAt = existing;
			claimed = false;
		} else {
			// No map yet. Create it with this player's start in place,
			// guarded so a concurrent call cannot clobber a map that
			// appeared in between.
			const created = await ddb.send(new UpdateCommand({
				TableName: MATCHES_TABLE_NAME,
				Key: { matchId },
				UpdateExpression: 'SET runStarts = :map',
				ConditionExpression: 'attribute_not_exists(runStarts)',
				ExpressionAttributeValues: { ':map': { [uuid]: now } },
				ReturnValues: 'ALL_NEW',
			}));
			runStartedAt = created.Attributes!.runStarts[uuid];
			claimed = true;
		}
	}

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			runStartedAt,
			serverNow: Date.now(),
			// False means this client had already started this run -
			// a rejoin after a crash or a quit. Useful to log.
			claimed,
		}),
	};
};
