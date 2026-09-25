import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { MatchPlayer } from './lib/matchCompletion';
import { isSplitName, validateSplit } from './lib/splitRules';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const MATCH_HISTORY_TABLE_NAME = process.env.MATCH_HISTORY_TABLE_NAME!;

// NOTHING in this handler ends the race any more.
//
// Killing the dragon used to complete the match. That is the wrong
// moment: the dragon dying is a split, and the race is not over until
// the runner has got back to the exit portal and jumped in. Two
// players can kill within seconds of each other and the one standing
// on the fountain wins.
//
// Completion moved to /matches/complete, which the client calls when
// the player actually enters the fountain. That endpoint requires a
// recorded kill_dragon split from the claimed winner, so the
// plausibility rules here still gate the win - they just no longer
// award it.


interface SplitRequest {
	matchId: string;
	splitName: string;
	elapsedMs: number;
}

export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	const reporterUuid = await resolveSessionToken(SESSIONS_TABLE_NAME, event.headers['authorization']);
	if (!reporterUuid) {
		return { statusCode: 401, body: JSON.stringify({ error: 'missing or invalid session token' }) };
	}

	let body: SplitRequest;
	try {
		body = JSON.parse(event.body ?? '{}');
	} catch {
		return { statusCode: 400, body: JSON.stringify({ error: 'invalid JSON body' }) };
	}
	if (!body.matchId || !body.splitName || typeof body.elapsedMs !== 'number') {
		return {
			statusCode: 400,
			body: JSON.stringify({ error: 'matchId, splitName and numeric elapsedMs are required' }),
		};
	}
	if (!isSplitName(body.splitName)) {
		return { statusCode: 400, body: JSON.stringify({ error: `unknown split: ${body.splitName}` }) };
	}

	const match = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
	}));
	if (!match.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}

	const players: MatchPlayer[] = match.Item.players;
	const reporter = players.find((p) => p.uuid === reporterUuid);
	if (!reporter) {
		return { statusCode: 403, body: JSON.stringify({ error: 'you are not a participant in this match' }) };
	}

	// Plausibility check before anything is recorded. Rules are in
	// lib/splitRules.ts and every rejection explains itself, so a player
	// can see exactly why a result was refused rather than being told
	// only that it was.
	const allSplits: Record<string, Record<string, number>> = match.Item.splits ?? {};
	const mine = allSplits[reporterUuid] ?? {};
	const check = validateSplit(
		body.splitName, body.elapsedMs, mine, match.Item.createdAt, Date.now());

	if (!check.ok) {
		// Rejections are kept on the match for audit and appeals - a ban
		// that can't be explained afterwards is the thing this project
		// exists to avoid.
		await ddb.send(new UpdateCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId: body.matchId },
			UpdateExpression: 'SET rejections = list_append(if_not_exists(rejections, :empty), :entry)',
			ExpressionAttributeValues: {
				':empty': [],
				':entry': [{
					uuid: reporterUuid,
					splitName: body.splitName,
					elapsedMs: body.elapsedMs,
					reason: check.reason,
					at: Date.now(),
				}],
			},
		}));
		return {
			statusCode: 422,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ recorded: false, rejected: true, reason: check.reason }),
		};
	}

	// Record the split. Nested map keyed by uuid then split name, with
	// if_not_exists on the per-player map so concurrent split reports
	// from both players can't clobber each other's timeline.
	await ddb.send(new UpdateCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
		UpdateExpression: 'SET splits = if_not_exists(splits, :empty)',
		ExpressionAttributeValues: { ':empty': {} },
	}));
	await ddb.send(new UpdateCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
		UpdateExpression: 'SET splits.#uuid = if_not_exists(splits.#uuid, :empty)',
		ExpressionAttributeNames: { '#uuid': reporterUuid },
		ExpressionAttributeValues: { ':empty': {} },
	}));
	await ddb.send(new UpdateCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
		UpdateExpression: 'SET splits.#uuid.#split = if_not_exists(splits.#uuid.#split, :elapsed)',
		ExpressionAttributeNames: { '#uuid': reporterUuid, '#split': body.splitName },
		ExpressionAttributeValues: { ':elapsed': body.elapsedMs },
	}));

	// Reporting a split is proof of life, so it feeds the same
	// abandonment clock the live poll does. Without this a client that
	// reports splits but never polls - the synthetic opponent does
	// exactly that - would look abandoned and hand away the match.
	await ddb.send(new UpdateCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
		UpdateExpression: 'SET lastSeenAt.#uuid = :now',
		ConditionExpression: 'attribute_exists(lastSeenAt)',
		ExpressionAttributeNames: { '#uuid': reporterUuid },
		ExpressionAttributeValues: { ':now': Date.now() },
	})).catch(() => {
		// Matches predating lastSeenAt have no map to write into.
	});

	// Recording only. Completion is the fountain's job now, through
	// /matches/complete - see the note at the top of this file. The
	// ordering, floor and gap rules that ran earlier in this handler
	// still gate the win; they just no longer award it.
	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({ recorded: true, completed: false }),
	};
};
