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

/**
 * Writes one history row per player.
 *
 * Denormalised on purpose: a history screen should not have to fetch
 * the match, then the opponent, then the result. Everything a row
 * needs to render is on the row.
 *
 * Best-effort. A completed match with a missing history row is a gap
 * in a list; a settlement that fails because a list row could not be
 * written would be a match with no rating change. The first is worth
 * risking to avoid the second, and the backfill script can repair it.
 */
async function writeHistory(
	historyTableName: string,
	matchId: string,
	match: Record<string, any>,
	winner: MatchPlayer,
	loser: MatchPlayer,
	winnerDelta: number,
	loserDelta: number,
	seasonPoints: number,
	completedAt: number,
): Promise<void> {
	const rows = [
		{ me: winner, them: loser, won: true, delta: winnerDelta, points: seasonPoints },
		{ me: loser, them: winner, won: false, delta: loserDelta, points: 0 },
	];
	for (const r of rows) {
		try {
			await ddb.send(new UpdateCommand({
				TableName: historyTableName,
				Key: { uuid: r.me.uuid, completedAt },
				UpdateExpression: 'SET matchId = :m, opponentUuid = :ou, opponentName = :on, '
					+ 'won = :w, ratingDelta = :d, seasonPointsAwarded = :p, '
					+ 'seedType = :st, overworldSeed = :os, netherSeed = :ns, '
					+ 'worldSetupVersion = :wsv',
				ExpressionAttributeValues: {
					':m': matchId,
					':ou': r.them.uuid,
					':on': r.them.username ?? 'opponent',
					':w': r.won,
					':d': r.delta,
					':p': r.points,
					':st': match.seedType ?? 'unknown',
					':os': match.overworldSeed ?? 0,
					':ns': match.netherSeed ?? 0,
					// Carried so a replay knows whether it can rebuild
					// this world with the current rules.
					':wsv': match.worldSetupVersion ?? 0,
				},
			}));
		} catch (err) {
			console.error(`[matchCompletion] history row failed for ${r.me.uuid}`, err);
		}
	}
}

export async function applyMatchCompletion(
	matchesTableName: string,
	playersTableName: string,
	matchId: string,
	winner: MatchPlayer,
	loser: MatchPlayer,
	splits: Record<string, Record<string, number>> = {},
	// REQUIRED, not optional. It was optional, and all four call sites
	// silently omitted it - so every match settled without writing a
	// history row and the screen showed only backfilled ones. An
	// optional parameter is a compile-time check declined.
	historyTableName: string,
): Promise<CompletionResult> {
	// One timestamp for both the match record and the history sort key,
	// so a row can be found from a match and vice versa.
	const completedAt = Date.now();
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
				':now': completedAt,
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

	// Keep the deltas on the match so a result screen can show what the
	// game actually did, rather than the client having to infer it.
	await ddb.send(new UpdateCommand({
		TableName: matchesTableName,
		Key: { matchId },
		UpdateExpression: 'SET #results = :r',
		ExpressionAttributeNames: { '#results': 'results' },
		ExpressionAttributeValues: {
			':r': {
				[winner.uuid]: { ratingDelta: winnerDelta, seasonPointsAwarded: seasonPoints },
				[loser.uuid]: { ratingDelta: loserDelta, seasonPointsAwarded: 0 },
			},
		},
	}));

	{
		const m = await ddb.send(new GetCommand({
			TableName: matchesTableName,
			Key: { matchId },
			ProjectionExpression: 'seedType, overworldSeed, netherSeed, worldSetupVersion',
		}));
		await writeHistory(historyTableName, matchId, m.Item ?? {},
			winner, loser, winnerDelta, loserDelta, seasonPoints, completedAt);
	}

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
