import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { applyMatchCompletion, MatchPlayer } from './lib/matchCompletion';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;
const MATCH_HISTORY_TABLE_NAME = process.env.MATCH_HISTORY_TABLE_NAME!;

/**
 * Gives up the current match, handing the win to the opponent.
 *
 * Speedrunners reset constantly - abandoning a bad start is part of how
 * the category is played, not an edge case. Without this a player is
 * stuck in a match they don't want and it sits pending forever.
 *
 * Forfeiting is a real loss, not a free escape: it goes through the
 * same completion path as any other result, so ratings move normally.
 * That's what stops it being used to dodge an unfavourable matchup.
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

	const match = await ddb.send(new GetCommand({
		TableName: MATCHES_TABLE_NAME,
		Key: { matchId: body.matchId },
	}));
	if (!match.Item) {
		return { statusCode: 404, body: JSON.stringify({ error: 'match not found' }) };
	}

	const players: MatchPlayer[] = match.Item.players;
	const quitter = players.find((p) => p.uuid === uuid);
	const opponent = players.find((p) => p.uuid !== uuid);
	if (!quitter || !opponent) {
		return { statusCode: 403, body: JSON.stringify({ error: 'you are not a participant in this match' }) };
	}

	const result = await applyMatchCompletion(
		MATCHES_TABLE_NAME, PLAYERS_TABLE_NAME, body.matchId,
		opponent, quitter, match.Item.splits ?? {}, MATCH_HISTORY_TABLE_NAME);

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
		body: JSON.stringify({
			forfeited: true,
			winner: result.winner,
			loser: result.loser,
		}),
	};
};
