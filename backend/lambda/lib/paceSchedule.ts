import { SplitName } from './splitRules';

/**
 * A pace is DATA, not a process.
 *
 * Race a Pace gives a player something to race when the queue is empty.
 * The opponent's whole run is a list of split times worked out when the
 * match is created and stored on the match row, so nothing has to be
 * running anywhere for the quarter of an hour the race takes. The
 * alternatives all fight a duration limit that this design simply does
 * not have. SPEC's "Race a Pace" section has the reasoning.
 *
 * Two rules this module exists to keep honest:
 *
 * The schedule is never handed to the client whole - see revealedSplits.
 * A player who could read it would know the result before starting.
 *
 * Tiers are named by their TIME, not by a skill word. "A 17:00 pace" is
 * a fact; "an intermediate pace" claims a mapping from rating to pace
 * that nothing here has measured. SPEC refuses to invent that curve, and
 * naming the tiers after times is how this file refuses too.
 */

/** Cumulative fractions of the finish time, per split. */
export type Schedule = Partial<Record<SplitName, number>>;

export interface PaceTier {
	/** Stable id stored on the match row. */
	id: string;
	/** What the player is shown. A time, deliberately. */
	label: string;
	finishMs: number;
}

/**
 * STAND-INS, and only until there are real runs to sample. They span
 * strong to new rather than clustering near the top, because the players
 * with nobody to race skew new and a ladder that only offers a fast pace
 * beats exactly those players every time.
 *
 * Replace with pace sampled from recorded runs per rating band as soon as
 * such runs exist; scheduleFromRun is the seam for that.
 */
export const PACE_TIERS: readonly PaceTier[] = [
	{ id: 'p10', label: '10:00', finishMs: 10 * 60_000 },
	{ id: 'p13', label: '13:00', finishMs: 13 * 60_000 },
	{ id: 'p17', label: '17:00', finishMs: 17 * 60_000 },
	{ id: 'p22', label: '22:00', finishMs: 22 * 60_000 },
	{ id: 'p30', label: '30:00', finishMs: 30 * 60_000 },
];

export function tierById(id: string): PaceTier | undefined {
	return PACE_TIERS.find((t) => t.id === id);
}

/**
 * The SHAPE of a run, as cumulative fractions of its finish time. Taken
 * from a real run rather than spaced evenly, because an even spread is
 * not what a run looks like - the nether is cheap and the stronghold
 * hunt is not.
 *
 * `backend/scripts/pace-bot.sh` carries the same numbers for the test
 * fixture it drives. If this shape changes, that changes with it; there
 * is no way to import one into the other across bash and TypeScript, so
 * they are kept honest by this sentence.
 *
 * Order matters and is not arbitrary: splitRules requires enter_nether
 * before barter and rod, and enter_stronghold before enter_end before
 * kill_dragon. A shape that violated that would build a schedule the
 * server's own rules call impossible.
 */
export const SPLIT_SHAPE: ReadonlyArray<readonly [SplitName, number]> = [
	['enter_nether', 0.167],
	['piglin_barter', 0.250],
	['obtain_rod', 0.417],
	['enter_stronghold', 0.708],
	['enter_end', 0.833],
	['kill_dragon', 1.0],
];

/**
 * Split times, in ms from the player's run start, for a tier.
 *
 * DETERMINISTIC, and that is a decision rather than an oversight. Drawing
 * a fresh schedule per match needs a distribution, and choosing one would
 * be a second invented number on top of the pace curve this file already
 * refuses to guess. So until real runs can be sampled, a tier repeats -
 * which is a reason to present it as a pace target rather than dress it
 * up as a rival, and a reason not to ship this wide yet. See SPEC.
 */
export function scheduleForTier(tier: PaceTier): Schedule {
	const out: Schedule = {};
	for (const [name, fraction] of SPLIT_SHAPE) {
		out[name] = Math.round(tier.finishMs * fraction);
	}
	return out;
}

/**
 * The seam for the real thing: play back an actual recorded run.
 *
 * Real runs carry real variance, including bad ones, so sampling them
 * gets a distribution from measurement instead of from a parameter
 * somebody picked. Splits arrive already cumulative and in ms, which is
 * the same shape a schedule is, so this mostly exists to name the
 * intent and to drop anything unrecognised.
 */
export function scheduleFromRun(splits: Record<string, number>): Schedule {
	const out: Schedule = {};
	for (const [name] of SPLIT_SHAPE) {
		const v = splits[name];
		if (typeof v === 'number' && Number.isFinite(v) && v > 0) {
			out[name] = Math.round(v);
		}
	}
	return out;
}

/** When the pace finishes, in ms from run start. */
export function paceFinishMs(schedule: Schedule): number {
	let max = 0;
	for (const v of Object.values(schedule)) {
		if (typeof v === 'number' && v > max) max = v;
	}
	return max;
}

/**
 * The splits a player is allowed to KNOW about yet.
 *
 * This is the whole reason the schedule can be stored server-side rather
 * than shipped to the client: the client is told the pace's progress the
 * same way it would be told a human's - what has happened, not what is
 * going to. Without this filter, storing the run as data would hand the
 * player the result before they mined a block.
 *
 * Strictly less-than-or-equal: a split due at exactly the current
 * elapsed time has happened.
 */
export function revealedSplits(schedule: Schedule, elapsedMs: number): Schedule {
	const out: Schedule = {};
	if (!Number.isFinite(elapsedMs) || elapsedMs < 0) return out;
	for (const [name, due] of Object.entries(schedule) as [SplitName, number][]) {
		if (typeof due === 'number' && due <= elapsedMs) out[name] = due;
	}
	return out;
}
