/**
 * Statistical review of completed runs.
 *
 * This layer exists because the plausibility rules in splitRules.ts
 * only catch impossible claims. A cheater who reports a believable run
 * passes those completely. What it cannot easily fake is consistency
 * with its own history.
 *
 * Two hard design constraints:
 *
 * 1. Nothing here ever bans, rejects or adjusts a result. It produces a
 *    flag and a written explanation for human review. Statistics cannot
 *    prove cheating - a flag is the start of a conversation, not a
 *    verdict.
 *
 * 2. Getting better is not cheating. Players legitimately improve, and
 *    a naive "this run was unusually fast" test punishes exactly the
 *    people the ladder is meant to reward. The primary signal is
 *    therefore internal inconsistency - one segment wildly out of line
 *    with the same run's other segments - rather than raw speed.
 */

export const TRACKED_SPLITS = [
	'enter_nether',
	'obtain_rod',
	'enter_stronghold',
	'enter_end',
	'kill_dragon',
] as const;

/** Welford accumulator: count, running mean, sum of squared deltas. */
export interface SplitStat {
	n: number;
	mean: number;
	m2: number;
}

export type SplitStats = Record<string, SplitStat>;

/**
 * Below this many prior runs there is no meaningful distribution to
 * compare against, so a player is never flagged. New accounts are the
 * most likely to be falsely accused and the least defensible to
 * accuse.
 */
export const MIN_SAMPLES = 8;

/**
 * Deliberately high. At 4 sigma a normally distributed legitimate run
 * trips this about 1 in 16000 times, so flags stay rare enough that a
 * human reviewing them is worth doing.
 */
const SIGMA_THRESHOLD = 4;

/** A z-score is meaningless if the player is near-perfectly consistent,
 * so ignore tiny deviations however many sigma they represent. */
const MIN_ABSOLUTE_GAP_MS = 45_000;

export function emptyStats(): SplitStats {
	return {};
}

/** Folds one observation into a Welford accumulator. */
export function update(stat: SplitStat | undefined, value: number): SplitStat {
	const prev = stat ?? { n: 0, mean: 0, m2: 0 };
	const n = prev.n + 1;
	const delta = value - prev.mean;
	const mean = prev.mean + delta / n;
	const delta2 = value - mean;
	return { n, mean, m2: prev.m2 + delta * delta2 };
}

export function stdDev(stat: SplitStat): number {
	return stat.n < 2 ? 0 : Math.sqrt(stat.m2 / (stat.n - 1));
}

/**
 * Segment durations rather than cumulative times. A faked finish shows
 * up as one impossible segment, whereas cumulative times smear the
 * anomaly across every split after it.
 */
export function segments(splits: Record<string, number>): Record<string, number> {
	const out: Record<string, number> = {};
	let prevTime = 0;
	let prevName = 'start';
	for (const split of TRACKED_SPLITS) {
		const t = splits[split];
		if (t === undefined) {
			continue;
		}
		out[`${prevName}->${split}`] = t - prevTime;
		prevTime = t;
		prevName = split;
	}
	return out;
}

export interface Finding {
	metric: string;
	observedMs: number;
	meanMs: number;
	stdDevMs: number;
	sigma: number;
	samples: number;
	note: string;
}

export interface ReviewResult {
	flagged: boolean;
	findings: Finding[];
	summary: string;
}

/**
 * Compares one run's segments against the player's own history.
 *
 * Only flags segments that are faster than usual. A slow segment is
 * just a bad run and carries no integrity signal.
 */
export function review(history: SplitStats, splits: Record<string, number>): ReviewResult {
	const findings: Finding[] = [];
	const observed = segments(splits);

	for (const [metric, value] of Object.entries(observed)) {
		const stat = history[metric];
		if (!stat || stat.n < MIN_SAMPLES) {
			continue; // not enough history to say anything
		}
		const sd = stdDev(stat);
		if (sd <= 0) {
			continue;
		}
		const gap = stat.mean - value;
		if (gap < MIN_ABSOLUTE_GAP_MS) {
			continue; // faster, but not by a meaningful margin
		}
		const sigma = gap / sd;
		if (sigma < SIGMA_THRESHOLD) {
			continue;
		}
		findings.push({
			metric,
			observedMs: Math.round(value),
			meanMs: Math.round(stat.mean),
			stdDevMs: Math.round(sd),
			sigma: Number(sigma.toFixed(2)),
			samples: stat.n,
			note: `${metric} took ${Math.round(value / 1000)}s, `
				+ `${sigma.toFixed(1)} standard deviations faster than this player's `
				+ `average of ${Math.round(stat.mean / 1000)}s over ${stat.n} prior runs`,
		});
	}

	if (findings.length === 0) {
		return { flagged: false, findings: [], summary: 'no anomalies' };
	}

	// One outlying segment in an otherwise normal run is the shape a
	// faked finish makes. A genuinely improved run tends to move several
	// segments at once, so that is called out as the weaker signal it is.
	const isolated = findings.length === 1 && Object.keys(observed).length > 2;
	const summary = isolated
		? `1 segment far out of line with the rest of the run - the pattern a faked `
			+ `finish makes. Needs human review; this is not proof.`
		: `${findings.length} segments faster than this player's history. Broad improvement `
			+ `looks like this too, so weigh accordingly. Needs human review.`;

	return { flagged: true, findings, summary };
}

/** Folds a completed run into a player's baseline. */
export function foldRun(history: SplitStats, splits: Record<string, number>): SplitStats {
	const next: SplitStats = { ...history };
	for (const [metric, value] of Object.entries(segments(splits))) {
		next[metric] = update(next[metric], value);
	}
	return next;
}
