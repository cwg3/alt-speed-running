import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';
import { applyMatchCompletion, MatchPlayer } from './lib/matchCompletion';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;

interface CompleteRequest {
	matchId: string;
	winnerUuid: string;
}

// Explicit result reporting. In normal play the match auto-completes
// from a kill_dragon split (see reportSplit.ts) and this endpoint isn't
// needed - it stays as the manual/administrative path (forfeits,
// disconnects, corrections).
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

	const result = await applyMatchCompletion(
		MATCHES_TABLE_NAME, PLAYERS_TABLE_NAME, body.matchId, winner, loser,
		match.Item.splits ?? {});

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
