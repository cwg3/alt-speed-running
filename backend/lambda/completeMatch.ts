import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';
import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { resolveSessionToken } from './lib/auth';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const SESSIONS_TABLE_NAME = process.env.SESSIONS_TABLE_NAME!;
const PLAYERS_TABLE_NAME = process.env.PLAYERS_TABLE_NAME!;
const MATCHES_TABLE_NAME = process.env.MATCHES_TABLE_NAME!;

// Standard chess-style Elo, K=32 - a well-known, transparent default.
// This is the HIDDEN rating used only for matchmaking quality.
const ELO_K_FACTOR = 32;

// Season points are the PUBLIC-facing number (what a leaderboard would
// show) - our own documented policy, not a copy of any other
// platform's formula. Only winners gain points (no loss penalty, so
// players aren't discouraged from playing often - this is deliberately
// different from the hidden Elo rating, which does move down on a
// loss), scaled by how much stronger the opponent was, floored at 1 so
// beating even a much weaker opponent still counts for something.
const SEASON_POINTS_BASE = 10;

interface MatchPlayer {
	uuid: string;
	username: string;
	skillRating: number;
}

interface CompleteRequest {
	matchId: string;
	winnerUuid: string;
}

// Known, deliberate limitation: this trusts whichever client reports
// first that they're a participant and who won - there's no
// server-side verification of the actual race outcome yet. Real
// anti-cheat/result-verification is later work (see project notes);
// this endpoint's job for now is just "given a trusted result, update
// ratings correctly."
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

	// Atomically claim completion - if the other player already reported
	// this match, this fails cleanly instead of double-applying rating
	// changes.
	try {
		await ddb.send(new UpdateCommand({
			TableName: MATCHES_TABLE_NAME,
			Key: { matchId: body.matchId },
			UpdateExpression: 'SET #status = :completed, winnerUuid = :winner, completedAt = :now',
			ConditionExpression: '#status = :pending',
			ExpressionAttributeNames: { '#status': 'status' },
			ExpressionAttributeValues: {
				':completed': 'completed',
				':pending': 'pending',
				':winner': body.winnerUuid,
				':now': Date.now(),
			},
		}));
	} catch (err) {
		if (err instanceof ConditionalCheckFailedException) {
			return {
				statusCode: 200,
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({ alreadyCompleted: true }),
			};
		}
		throw err;
	}

	// Elo update using the rating snapshot taken when the match was
	// created (see queueJoin.ts) - not live-refetched, since ratings
	// shouldn't legitimately change mid-match.
	const expectedWinner = 1 / (1 + Math.pow(10, (loser.skillRating - winner.skillRating) / 400));
	const winnerDelta = Math.round(ELO_K_FACTOR * (1 - expectedWinner));
	const loserDelta = -winnerDelta;

	const seasonPoints = Math.max(
		1,
		Math.round(SEASON_POINTS_BASE + (loser.skillRating - winner.skillRating) / 20),
	);

	await ddb.send(new UpdateCommand({
		TableName: PLAYERS_TABLE_NAME,
		Key: { uuid: winner.uuid },
		UpdateExpression: 'SET skillRating = skillRating + :delta, seasonPoints = seasonPoints + :points',
		ExpressionAttributeValues: { ':delta': winnerDelta, ':points': seasonPoints },
	}));
	await ddb.send(new UpdateCommand({
		TableName: PLAYERS_TABLE_NAME,
		Key: { uuid: loser.uuid },
		UpdateExpression: 'SET skillRating = skillRating + :delta',
		ExpressionAttributeValues: { ':delta': loserDelta },
	}));

	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			winner: { uuid: winner.uuid, ratingDelta: winnerDelta, seasonPointsAwarded: seasonPoints },
			loser: { uuid: loser.uuid, ratingDelta: loserDelta },
		}),
	};
};
