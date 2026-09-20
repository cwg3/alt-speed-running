import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { emptyStats, foldRun, review, ReviewResult } from './runStats';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

// Standard chess-style Elo, K=32 - a well-known, transparent default.
// This is the HIDDEN rating used only for matchmaking quality.
const ELO_K_FACTOR = 32;

// Season points are the PUBLIC-facing number (what a leaderboard would
// show) - our own documented policy. Only winners gain points (no loss
// penalty, so players aren't discouraged from playing often - this is
// deliberately different from the hidden Elo rating, which does move
// down on a loss), scaled by how much stronger the opponent was,
// floored at 1 so beating even a much weaker opponent still counts.
const SEASON_POINTS_BASE = 10;

export interface MatchPlayer {
	uuid: string;
	username: string;
	skillRating: number;
}

export interface CompletionResult {
	alreadyCompleted: boolean;
	winner?: { uuid: string; ratingDelta: number; seasonPointsAwarded: number };
	loser?: { uuid: string; ratingDelta: number };
}

/**
 * Atomically claims match completion and applies rating/season point
 * changes. Shared by the explicit /matches/complete endpoint and the
 * automatic completion triggered by a kill_dragon split, so both paths
 * use identical math and identical double-apply protection.
 *
 * The conditional update (status must still be 'pending') is what makes
 * "first player to report the dragon kill wins" correct race semantics
 * AND prevents a second report from double-applying rating changes.
 */
/**
 * Runs the statistical review for one player and updates their
 * baseline.
 *
 * A flagged run is deliberately NOT folded into the baseline. Folding
 * it would let a repeat cheater drag their own average toward their
 * faked times until later fakes look unremarkable - the detector would
 * quietly train itself to accept them.
 */
async function reviewPlayer(
	playersTableName: string,
	uuid: string,
	splits: Record<string, number> | undefined,
): Promise<ReviewResult | null> {
	if (!splits || Object.keys(splits).length === 0) {
		return null;
	}

	const record = await ddb.send(new GetCommand({
		TableName: playersTableName,
		Key: { uuid },
		ProjectionExpression: 'splitStats',
	}));
	const history = record.Item?.splitStats ?? emptyStats();

	const result = review(history, splits);
	if (!result.flagged) {
		await ddb.send(new UpdateCommand({
			TableName: playersTableName,
			Key: { uuid },
			UpdateExpression: 'SET splitStats = :stats',
			ExpressionAttributeValues: { ':stats': foldRun(history, splits) },
		}));
	}
	return result;
}

export async function applyMatchCompletion(
	matchesTableName: string,
	playersTableName: string,
	matchId: string,
	winner: MatchPlayer,
	loser: MatchPlayer,
	splits: Record<string, Record<string, number>> = {},
): Promise<CompletionResult> {
	try {
		await ddb.send(new UpdateCommand({
			TableName: matchesTableName,
			Key: { matchId },
			UpdateExpression: 'SET #status = :completed, winnerUuid = :winner, completedAt = :now',
			ConditionExpression: '#status = :pending',
			ExpressionAttributeNames: { '#status': 'status' },
			ExpressionAttributeValues: {
				':completed': 'completed',
				':pending': 'pending',
				':winner': winner.uuid,
				':now': Date.now(),
			},
		}));
	} catch (err) {
		if (err instanceof ConditionalCheckFailedException) {
			return { alreadyCompleted: true };
		}
		throw err;
	}

	// Elo uses the rating snapshot taken when the match was created (see
	// queueJoin.ts) - not live-refetched, since ratings shouldn't
	// legitimately change mid-match.
	const expectedWinner = 1 / (1 + Math.pow(10, (loser.skillRating - winner.skillRating) / 400));
	const winnerDelta = Math.round(ELO_K_FACTOR * (1 - expectedWinner));
	const loserDelta = -winnerDelta;

	const seasonPoints = Math.max(
		1,
		Math.round(SEASON_POINTS_BASE + (loser.skillRating - winner.skillRating) / 20),
	);

	await ddb.send(new UpdateCommand({
		TableName: playersTableName,
		Key: { uuid: winner.uuid },
		UpdateExpression: 'SET skillRating = skillRating + :delta, seasonPoints = seasonPoints + :points',
		ExpressionAttributeValues: { ':delta': winnerDelta, ':points': seasonPoints },
	}));
	await ddb.send(new UpdateCommand({
		TableName: playersTableName,
		Key: { uuid: loser.uuid },
		UpdateExpression: 'SET skillRating = skillRating + :delta',
		ExpressionAttributeValues: { ':delta': loserDelta },
	}));

	// Clear the pointer so neither player is handed this finished match
	// again on their next queue poll.
	for (const player of [winner, loser]) {
		await ddb.send(new UpdateCommand({
			TableName: playersTableName,
			Key: { uuid: player.uuid },
			UpdateExpression: 'REMOVE currentMatchId',
		}));
	}

	// Review runs against each player's own history. Purely advisory -
	// it records a flag for human review and never alters the result.
	const reviews: Record<string, ReviewResult> = {};
	for (const player of [winner, loser]) {
		const result = await reviewPlayer(playersTableName, player.uuid, splits[player.uuid]);
		if (result) {
			reviews[player.uuid] = result;
		}
	}
	const anyFlagged = Object.values(reviews).some((r) => r.flagged);
	if (Object.keys(reviews).length > 0) {
		await ddb.send(new UpdateCommand({
			TableName: matchesTableName,
			Key: { matchId },
			UpdateExpression: 'SET review = :review, needsReview = :needsReview',
			ExpressionAttributeValues: { ':review': reviews, ':needsReview': anyFlagged },
		}));
	}

	return {
		alreadyCompleted: false,
		winner: { uuid: winner.uuid, ratingDelta: winnerDelta, seasonPointsAwarded: seasonPoints },
		loser: { uuid: loser.uuid, ratingDelta: loserDelta },
	};
}
