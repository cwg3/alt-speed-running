import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { emptyStats, foldRun, review, ReviewResult } from './runStats';
// One list of synthetic players, defined in seedPool.ts and imported
// rather than restated. A second copy would drift, and the two places
// that care - who is excluded from seenSeeds, and who cannot move a
// rating - must not be allowed to disagree about who the bots are.
import { isSyntheticPlayer } from './seedPool';

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

export interface MatchScore {
	winnerDelta: number;
	loserDelta: number;
	seasonPoints: number;
}

/**
 * The scoring math, kept separate from the writes so it can be tested
 * without standing up a DynamoDB double - which is the only reason the
 * exhibition rule had no test when it was the rating's only safeguard.
 *
 * An exhibition scores nothing: no Elo either way, no season points.
 * Returning zeros rather than skipping the call keeps one code path for
 * the match record, so a result screen reads the same fields whatever
 * kind of match it was.
 */
export function scoreMatch(
	winnerRating: number,
	loserRating: number,
	exhibition: boolean,
): MatchScore {
	if (exhibition) {
		return { winnerDelta: 0, loserDelta: 0, seasonPoints: 0 };
	}
	const expectedWinner = 1 / (1 + Math.pow(10, (loserRating - winnerRating) / 400));
	const winnerDelta = Math.round(ELO_K_FACTOR * (1 - expectedWinner));
	return {
		winnerDelta,
		loserDelta: -winnerDelta,
		seasonPoints: Math.max(
			1,
			Math.round(SEASON_POINTS_BASE + (loserRating - winnerRating) / 20),
		),
	};
}

export interface MatchPlayer {
	uuid: string;
	username: string;
	skillRating: number;
}

export interface CompletionResult {
	alreadyCompleted: boolean;
	/** A synthetic player was involved, so nothing was scored. */
	exhibition?: boolean;
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
	/** Set when the match ended because someone quit. */
	forfeitedBy?: string,
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
					+ 'worldSetupVersion = :wsv, forfeitedBy = :ff',
				ExpressionAttributeValues: {
					':m': matchId,
					// null, not absent: 'this match was not forfeited'
					// and 'this row predates the field' are different
					// facts, and a reader that cannot tell them apart
					// will show old losses as forfeits or the reverse.
					':ff': forfeitedBy ?? null,
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
	/**
	 * Who quit, when that is how the match ended.
	 *
	 * A forfeit and a loss are not the same result and should not
	 * read as one. Nothing recorded it before, so history showed a
	 * player who conceded at 3:29 exactly as it showed one who was
	 * beaten to the dragon.
	 */
	forfeitedBy?: string,
): Promise<CompletionResult> {
	// One timestamp for both the match record and the history sort key,
	// so a row can be found from a match and vice versa.
	const completedAt = Date.now();
	try {
		await ddb.send(new UpdateCommand({
			TableName: matchesTableName,
			Key: { matchId },
			UpdateExpression: 'SET #status = :completed, winnerUuid = :winner, completedAt = :now, '
				+ 'forfeitedBy = :forfeit',
			ConditionExpression: '#status = :pending',
			ExpressionAttributeNames: { '#status': 'status' },
			ExpressionAttributeValues: {
				':completed': 'completed',
				':pending': 'pending',
				':winner': winner.uuid,
				':now': completedAt,
				':forfeit': forfeitedBy ?? null,
			},
		}));
	} catch (err) {
		if (err instanceof ConditionalCheckFailedException) {
			return { alreadyCompleted: true };
		}
		throw err;
	}

	// An EXHIBITION is any match with a synthetic player on either side.
	// It is recorded in full - match row, splits, replay, review - and
	// scores nothing.
	//
	// Nothing checked this before, so a bot match moved the ladder
	// exactly like a human one. That is not hypothetical: ~180 synthetic
	// matches had to be undone by scripts/resetLadder.ts, and the only
	// thing containing it is that PaceBot must be launched by hand.
	// Anything that lets a player summon a bot turns this into a rating
	// farm, so the guard belongs HERE, at the one place that applies
	// score, rather than in whichever feature eventually offers one.
	//
	// The COUNTS are withheld along with the rating, not just the
	// rating. The leaderboard shows a W-L-F record, and a record quietly
	// padded with bot wins is the same falsehood in a different column.
	// Half of this fix would read as done and would not be.
	const exhibition = isSyntheticPlayer(winner.uuid) || isSyntheticPlayer(loser.uuid);

	// Elo uses the rating snapshot taken when the match was created (see
	// queueJoin.ts) - not live-refetched, since ratings shouldn't
	// legitimately change mid-match.
	const { winnerDelta, loserDelta, seasonPoints } =
		scoreMatch(winner.skillRating, loser.skillRating, exhibition);

	// Record counts as well as rating. A leaderboard that shows only a
	// rating cannot say whether 1229 came from two matches or two
	// hundred, and the match history table is keyed per player, so
	// counting from it would mean scanning every player's rows.
	//
	// ADD treats a missing attribute as 0, so rows written before these
	// counters existed start at zero rather than failing the update.
	//
	// A forfeit is counted apart from a loss. The rating change is
	// identical - the opponent still wins - but giving up on a bad seed
	// and being beaten to the dragon are different things, and the match
	// history screen already refuses to conflate them.
	if (!exhibition) {
		await ddb.send(new UpdateCommand({
			TableName: playersTableName,
			Key: { uuid: winner.uuid },
			UpdateExpression: 'SET skillRating = skillRating + :delta, seasonPoints = seasonPoints + :points '
				+ 'ADD wins :one, matches :one',
			ExpressionAttributeValues: { ':delta': winnerDelta, ':points': seasonPoints, ':one': 1 },
		}));
		await ddb.send(new UpdateCommand({
			TableName: playersTableName,
			Key: { uuid: loser.uuid },
			UpdateExpression: 'SET skillRating = skillRating + :delta '
				+ (forfeitedBy === loser.uuid
					? 'ADD forfeits :one, matches :one'
					: 'ADD losses :one, matches :one'),
			ExpressionAttributeValues: { ':delta': loserDelta, ':one': 1 },
		}));
	}

	// Keep the deltas on the match so a result screen can show what the
	// game actually did, rather than the client having to infer it.
	await ddb.send(new UpdateCommand({
		TableName: matchesTableName,
		Key: { matchId },
		// The flag is stored, not inferred from the zero deltas: a genuine
		// human match between equals can also produce a 0 delta, and a
		// result screen must never have to guess which kind it was.
		UpdateExpression: 'SET #results = :r, #exhibition = :ex',
		ExpressionAttributeNames: { '#results': 'results', '#exhibition': 'exhibition' },
		ExpressionAttributeValues: {
			':ex': exhibition,
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
			winner, loser, winnerDelta, loserDelta, seasonPoints, completedAt, forfeitedBy);
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
		exhibition,
		winner: { uuid: winner.uuid, ratingDelta: winnerDelta, seasonPointsAwarded: seasonPoints },
		loser: { uuid: loser.uuid, ratingDelta: loserDelta },
	};
}
