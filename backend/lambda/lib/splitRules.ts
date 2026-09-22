/**
 * Plausibility rules for reported splits.
 *
 * Design stance: every rule here is deterministic, published, and
 * explains itself in the rejection. The point of this project is that a
 * player can see exactly why a result was refused and argue about it -
 * so nothing here is a hidden heuristic or a silent score adjustment.
 *
 * These rules catch impossible claims, not suspicious ones. They are
 * not a substitute for real cheat detection; a cheater who fakes a
 * plausible run still gets through. What they stop is the trivial
 * attack of reporting kill_dragon the instant a match starts.
 */

export const SPLIT_ORDER = [
	'enter_nether',
	'piglin_barter',
	'obtain_rod',
	'enter_stronghold',
	'enter_end',
	'kill_dragon',
] as const;

export type SplitName = typeof SPLIT_ORDER[number];

/**
 * Prerequisites that the game itself guarantees, not ones that merely
 * describe the usual route.
 *
 * Deliberately absent: enter_stronghold does NOT require obtain_rod.
 * Eyes of ender normally need blaze powder, but a stronghold can be
 * found without them, and enforcing the common strategy rather than a
 * hard game rule is how you reject a legitimate unusual run.
 */
const REQUIRES: Partial<Record<SplitName, SplitName[]>> = {
	// Piglins and blazes only exist in the nether.
	piglin_barter: ['enter_nether'],
	obtain_rod: ['enter_nether'],
	// The end portal is inside the stronghold.
	enter_end: ['enter_stronghold'],
	// The dragon is in the end.
	kill_dragon: ['enter_end'],
};

/**
 * Absolute earliest each split could occur, in ms from run start.
 *
 * Anchored to the real 1.16-1.19 RSG world record of 6:39 (399s) with
 * heavy headroom - the dragon floor is roughly 45% of that record, so
 * beating it would mean halving a heavily optimised world record.
 * These exist to catch "dragon at 10 seconds", not to police fast runs.
 */
const MIN_ELAPSED_MS: Record<SplitName, number> = {
	enter_nether: 15_000,
	piglin_barter: 20_000,
	obtain_rod: 60_000,
	enter_stronghold: 100_000,
	enter_end: 110_000,
	kill_dragon: 180_000,
};

/** Minimum time between a split and its immediate predecessor. */
const MIN_GAP_MS: Partial<Record<SplitName, { after: SplitName; ms: number }>> = {
	piglin_barter: { after: 'enter_nether', ms: 3_000 },
	obtain_rod: { after: 'enter_nether', ms: 20_000 },
	enter_end: { after: 'enter_stronghold', ms: 3_000 },
	kill_dragon: { after: 'enter_end', ms: 20_000 },
};

// Allows for clock skew and request latency between the client's run
// timer and the server's view of when the match started.
//
// This is deliberately small, and the asymmetry matters: the check only
// rejects claiming MORE elapsed time than has really passed, which is
// the cheating direction - asserting you are further along than the
// clock allows. Reporting a split late is always fine.
//
// It was 60s, which let a client claim to be a full minute ahead of
// real time on every split without flagging. A synthetic opponent did
// exactly that six times in a row and the match came back clean. A
// minute of fake progress is enough to pressure an opponent into
// resetting a good run, so the tolerance is now only as large as
// latency and skew actually need.
//
// The client's own timer starts AFTER the server writes the match (the
// world still has to generate), so honest clients report less elapsed
// time than the server measures, not more. That gives this bound a
// wide margin in the safe direction.
const WALL_CLOCK_TOLERANCE_MS = 10_000;

export interface SplitCheck {
	ok: boolean;
	reason?: string;
}

export function isSplitName(value: string): value is SplitName {
	return (SPLIT_ORDER as readonly string[]).includes(value);
}

export function validateSplit(
	split: SplitName,
	elapsedMs: number,
	existing: Record<string, number>,
	matchCreatedAt: number,
	now: number,
): SplitCheck {
	if (!Number.isFinite(elapsedMs) || elapsedMs < 0) {
		return { ok: false, reason: `elapsedMs must be a non-negative number` };
	}

	if (existing[split] !== undefined) {
		return { ok: false, reason: `${split} was already reported at ${existing[split]}ms` };
	}

	// A run timer cannot have advanced further than real time has since
	// the match was created.
	const realElapsed = now - matchCreatedAt;
	if (elapsedMs > realElapsed + WALL_CLOCK_TOLERANCE_MS) {
		return {
			ok: false,
			reason: `${split} claims ${elapsedMs}ms elapsed but only ${realElapsed}ms `
				+ `has passed since the match started`,
		};
	}

	const floor = MIN_ELAPSED_MS[split];
	if (elapsedMs < floor) {
		return {
			ok: false,
			reason: `${split} at ${elapsedMs}ms is below the ${floor}ms minimum `
				+ `(faster than physically possible)`,
		};
	}

	for (const required of REQUIRES[split] ?? []) {
		const priorTime = existing[required];
		if (priorTime === undefined) {
			return { ok: false, reason: `${split} requires ${required}, which was never reported` };
		}
		if (elapsedMs < priorTime) {
			return {
				ok: false,
				reason: `${split} at ${elapsedMs}ms precedes its prerequisite `
					+ `${required} at ${priorTime}ms`,
			};
		}
	}

	const gap = MIN_GAP_MS[split];
	if (gap) {
		const priorTime = existing[gap.after];
		if (priorTime !== undefined && elapsedMs - priorTime < gap.ms) {
			return {
				ok: false,
				reason: `${split} came ${elapsedMs - priorTime}ms after ${gap.after}, `
					+ `below the ${gap.ms}ms minimum`,
			};
		}
	}

	return { ok: true };
}
