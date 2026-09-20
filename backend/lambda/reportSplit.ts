import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { applyMatchCompletion, MatchPlayer } from './lib/matchCompletion';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;

// Mirrors the mod's SplitEvents - the server decides what's a valid
// split name rather than trusting whatever a client sends.
const VALID_SPLITS = new Set([
	'enter_nether',
	'piglin_barter',
	'obtain_rod',
	'enter_stronghold',
	'enter_end',
	'kill_dragon',
]);

// Reaching this split ends the race, so it completes the match with
// the reporting player as the winner.
const FINAL_SPLIT = 'kill_dragon';

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
	if (!VALID_SPLITS.has(body.splitName)) {
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

	if (body.splitName !== FINAL_SPLIT) {
		return {
			statusCode: 200,
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ recorded: true, completed: false }),
		};
	}

	// Dragon kill ends the race - first report wins, enforced by the
	// conditional update inside applyMatchCompletion.
	const loser = players.find((p) => p.uuid !== reporterUuid)!;
	const result = await applyMatchCompletion(
		MATCHES_TABLE_NAME, PLAYERS_TABLE_NAME, body.matchId, reporter, loser);

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			recorded: true,
			completed: !result.alreadyCompleted,
			alreadyCompleted: result.alreadyCompleted,
			winner: result.winner,
			loser: result.loser,
		}),
	};
};
