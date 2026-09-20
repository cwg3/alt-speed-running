import {
	foldRun, MIN_SAMPLES, review, segments, SplitStats, stdDev, update,
} from '../lambda/lib/runStats';

/** Builds cumulative split times from per-segment durations. */
function runFrom(segmentMs: number[]): Record<string, number> {
	let t = 0;
	const cumulative = segmentMs.map((s) => (t += s));
	return {
		enter_nether: cumulative[0],
		obtain_rod: cumulative[1],
		enter_stronghold: cumulative[2],
		enter_end: cumulative[3],
		kill_dragon: cumulative[4],
	};
}

/** Typical ~11 minute run: 2:00 nether, 3:00 to rod, 3:20 to stronghold,
 * 0:40 to the end, 2:00 for the dragon. */
const TYPICAL_SEGMENTS = [120_000, 180_000, 200_000, 40_000, 120_000];

/**
 * Each segment must vary independently. An earlier version of this
 * fixture offset every split by the same amount, which left the
 * differences between them identical - every segment had zero variance
 * and nothing could ever be flagged. That was a broken fixture, not a
 * broken detector.
 */
function typicalRun(seed = 0): Record<string, number> {
	return runFrom(TYPICAL_SEGMENTS.map(
		(base, i) => base + Math.round(Math.sin(seed * 7.3 + i * 2.1) * 9_000)));
}

/** Normal until the end, then a 5s dragon fight instead of the usual
 * ~120s - the shape a faked finish makes. */
function fakedFinish(): Record<string, number> {
	return runFrom([...TYPICAL_SEGMENTS.slice(0, 4), 5_000]);
}

function baselineFrom(runs: Record<string, number>[]): SplitStats {
	return runs.reduce<SplitStats>((stats, run) => foldRun(stats, run), {});
}

describe('Welford accumulator', () => {
	it('matches mean and sample standard deviation computed directly', () => {
		const values = [10, 12, 23, 23, 16, 23, 21, 16];
		const stat = values.reduce((acc, v) => update(acc, v), undefined as any);

		const mean = values.reduce((a, b) => a + b, 0) / values.length;
		const variance = values.reduce((a, v) => a + (v - mean) ** 2, 0) / (values.length - 1);

		expect(stat.n).toBe(values.length);
		expect(stat.mean).toBeCloseTo(mean, 10);
		expect(stdDev(stat)).toBeCloseTo(Math.sqrt(variance), 10);
	});
});

describe('segments', () => {
	it('converts cumulative split times into per-segment durations', () => {
		expect(segments(runFrom(TYPICAL_SEGMENTS))).toEqual({
			'start->enter_nether': 120_000,
			'enter_nether->obtain_rod': 180_000,
			'obtain_rod->enter_stronghold': 200_000,
			'enter_stronghold->enter_end': 40_000,
			'enter_end->kill_dragon': 120_000,
		});
	});

	it('skips splits the run never reached', () => {
		const partial = segments({ enter_nether: 120_000, obtain_rod: 300_000 });
		expect(Object.keys(partial)).toEqual(['start->enter_nether', 'enter_nether->obtain_rod']);
	});
});

describe('review', () => {
	// Small spread so the baseline has a real, non-zero standard deviation.
	const history = baselineFrom(Array.from({ length: 12 }, (_, i) => typicalRun(i)));

	it('does not flag a run in line with history', () => {
		expect(review(history, typicalRun(99)).flagged).toBe(false);
	});

	it('does not flag a slower than usual run', () => {
		// A bad run carries no integrity signal.
		const slow = runFrom(TYPICAL_SEGMENTS.map((s) => s * 2));
		expect(review(history, slow).flagged).toBe(false);
	});

	it('does not flag a player without enough history', () => {
		const thin = baselineFrom(Array.from({ length: MIN_SAMPLES - 1 }, (_, i) => typicalRun(i)));
		expect(review(thin, fakedFinish()).flagged).toBe(false);
	});

	it('flags a single segment far faster than the player has ever managed', () => {
		const result = review(history, fakedFinish());

		expect(result.flagged).toBe(true);
		expect(result.findings).toHaveLength(1);
		expect(result.findings[0].metric).toBe('enter_end->kill_dragon');
		expect(result.findings[0].sigma).toBeGreaterThan(4);
		expect(result.summary).toContain('faked');
	});

	it('describes broad improvement differently from an isolated anomaly', () => {
		// Every segment much faster - what genuine improvement looks like.
		const improved: Record<string, number> = {
			enter_nether: 60_000,
			obtain_rod: 150_000,
			enter_stronghold: 250_000,
			enter_end: 270_000,
			kill_dragon: 330_000,
		};
		const result = review(history, improved);

		expect(result.findings.length).toBeGreaterThan(1);
		// Still surfaced for review, but not described as a faked finish.
		expect(result.summary).toContain('improvement');
		expect(result.summary).not.toContain('faked');
	});

	it('ignores a faster segment when the absolute gap is small', () => {
		// 10s better than average is not evidence of anything, however
		// consistent the player normally is.
		const slightly = runFrom([...TYPICAL_SEGMENTS.slice(0, 4), 110_000]);
		expect(review(history, slightly).flagged).toBe(false);
	});
});
