import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { MatchPlayer } from './lib/matchCompletion';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;

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

	const splits: Record<string, Record<string, number>> = match.Item.splits ?? {};

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			status: match.Item.status,
			winnerUuid: match.Item.winnerUuid ?? null,
			you: {
				uuid: you.uuid,
				username: you.username,
				splits: splits[you.uuid] ?? {},
			},
			opponent: {
				uuid: opponent.uuid,
				username: opponent.username,
				splits: splits[opponent.uuid] ?? {},
			},
		}),
	};
};
