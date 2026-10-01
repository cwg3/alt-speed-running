import {
	PACE_JITTER_MS, PACE_TIERS, SPLIT_SHAPE, paceFinishMs, revealedSplits,
	scheduleForTier, scheduleFromRun, tierById,
} from '../lambda/lib/paceSchedule';
import { SPLIT_ORDER, validateSplit } from '../lambda/lib/splitRules';

describe('pace tiers', () => {
	it('are named by time, not by a skill word', () => {
		// The label is shown to the player. A word like "intermediate"
		// would assert a rating-to-pace mapping nothing has measured.
		for (const t of PACE_TIERS) {
			expect(t.label).toMatch(/^\d{1,2}:\d{2}$/);
		}
	});

	it('span slow as well as fast', () => {
		// The players with nobody to race skew new. A set of tiers that
		// only went fast would beat exactly those players every time,
		// which is the failure this spread exists to avoid.
		const slowest = Math.max(...PACE_TIERS.map((t) => t.finishMs));
		const fastest = Math.min(...PACE_TIERS.map((t) => t.finishMs));
		expect(fastest).toBeLessThanOrEqual(10 * 60_000);
		expect(slowest).toBeGreaterThanOrEqual(30 * 60_000);
	});

	it('has labels matching finishMs', () => {
		for (const t of PACE_TIERS) {
			const [m, s] = t.label.split(':').map(Number);
			expect(t.finishMs).toBe((m * 60 + s) * 1000);
		}
	});

	it('resolves by id and rejects an unknown one', () => {
		expect(tierById(PACE_TIERS[0].id)).toEqual(PACE_TIERS[0]);
		expect(tierById('nope')).toBeUndefined();
	});
});

describe('the split shape', () => {
	it('uses only real split names', () => {
		for (const [name] of SPLIT_SHAPE) {
			expect(SPLIT_ORDER).toContain(name);
		}
	});

	it('produces a run the server itself would accept', () => {
		// Checked against the real validator rather than a copy of its
		// prerequisite table. A schedule that described an impossible run
		// - a split before something it requires, or under a floor - would
		// be a pace the server's own rules say cannot have happened.
		for (const tier of PACE_TIERS) {
			const sched = scheduleForTier(tier);
			const createdAt = 0;
			const reported: Record<string, number> = {};
			// Replay it in due order, as the pace actually unfolds.
			const inOrder = SPLIT_SHAPE.map(([n]) => n)
				.sort((a, b) => sched[a]! - sched[b]!);
			for (const name of inOrder) {
				const due = sched[name]!;
				// now = createdAt + due: the moment that split comes due,
				// so wall-clock tolerance cannot be what passes it.
				const check = validateSplit(name, due, reported, createdAt, createdAt + due);
				expect(check.reason ?? 'ok').toBe('ok');
				expect(check.ok).toBe(true);
				reported[name] = due;
			}
		}
	});

	it('is strictly increasing and ends at the finish', () => {
		const fractions = SPLIT_SHAPE.map(([, f]) => f);
		for (let i = 1; i < fractions.length; i++) {
			expect(fractions[i]).toBeGreaterThan(fractions[i - 1]);
		}
		expect(fractions[fractions.length - 1]).toBe(1.0);
	});

	it('is not evenly spaced', () => {
		// An even spread is not what a run looks like, and pace-bot.sh
		// notes it trips the plausibility floors.
		const gaps = SPLIT_SHAPE.map(([, f], i) => f - (i ? SPLIT_SHAPE[i - 1][1] : 0));
		expect(new Set(gaps.map((g) => g.toFixed(3))).size).toBeGreaterThan(1);
	});
});

describe('scheduleForTier', () => {
	// The extremes of the injected random source, plus a few in between.
	// Math.random never returns 1, so the top is just under it.
	const pinned = [0, 0.999999, 0.5, 0.25, 0.75];
	const constant = (v: number) => () => v;
	const alternating = () => { let i = 0; return () => (i++ % 2 ? 0.999999 : 0); };

	const draws = (tier: typeof PACE_TIERS[number]) => [
		...pinned.map((v) => scheduleForTier(tier, constant(v))),
		scheduleForTier(tier, alternating()),
		...Array.from({ length: 200 }, () => scheduleForTier(tier)),
	];

	it('finishes within a minute of the tier time', () => {
		for (const t of PACE_TIERS) {
			for (const s of draws(t)) {
				expect(Math.abs(paceFinishMs(s) - t.finishMs)).toBeLessThanOrEqual(PACE_JITTER_MS);
			}
		}
	});

	it('keeps every split within a minute of the fixed shape', () => {
		for (const t of PACE_TIERS) {
			for (const s of draws(t)) {
				for (const [name, fraction] of SPLIT_SHAPE) {
					expect(Math.abs(s[name]! - t.finishMs * fraction)).toBeLessThanOrEqual(PACE_JITTER_MS + 1);
				}
			}
		}
	});

	it('keeps the splits in order for every draw', () => {
		// 10:00 is the tight one: two splits there are under a minute
		// apart, so a minute of jitter each way could cross them if the
		// clamp did not preserve order.
		for (const t of PACE_TIERS) {
			for (const s of draws(t)) {
				const times = SPLIT_SHAPE.map(([n]) => s[n]!);
				for (let i = 1; i < times.length; i++) {
					expect(times[i]).toBeGreaterThan(times[i - 1]);
				}
			}
		}
	});

	it('differs from one race to the next', () => {
		const a = scheduleForTier(PACE_TIERS[1]);
		const b = scheduleForTier(PACE_TIERS[1]);
		expect(a).not.toEqual(b);
	});

	it('gives every shaped split a time', () => {
		const sched = scheduleForTier(PACE_TIERS[0]);
		expect(Object.keys(sched).sort()).toEqual(SPLIT_SHAPE.map(([n]) => n).sort());
	});
});

describe('scheduleFromRun', () => {
	it('keeps recognised splits and drops the rest', () => {
		const out = scheduleFromRun({
			enter_nether: 120_000, kill_dragon: 700_000, nonsense: 5, obtain_rod: 0,
		});
		expect(out.enter_nether).toBe(120_000);
		expect(out.kill_dragon).toBe(700_000);
		expect(out).not.toHaveProperty('nonsense');
		// 0 is not a time a split happened at.
		expect(out).not.toHaveProperty('obtain_rod');
	});

	it('ignores non-finite values rather than storing NaN', () => {
		const out = scheduleFromRun({ enter_nether: NaN, piglin_barter: Infinity });
		expect(out).toEqual({});
	});
});

describe('revealedSplits - the player must not read ahead', () => {
	const tier = PACE_TIERS[2]; // 17:00
	const sched = scheduleForTier(tier);

	it('shows nothing at the start', () => {
		expect(revealedSplits(sched, 0)).toEqual({});
	});

	it('shows a split the moment it is due, and not before', () => {
		const due = sched.enter_nether!;
		expect(revealedSplits(sched, due - 1)).not.toHaveProperty('enter_nether');
		expect(revealedSplits(sched, due)).toHaveProperty('enter_nether');
	});

	it('never reveals a later split than the elapsed time', () => {
		// The property that matters: whatever the clock, nothing in the
		// output is in the future.
		for (const t of [0, 1, 60_000, 500_000, paceFinishMs(sched) - 1, paceFinishMs(sched), 10 ** 9]) {
			for (const v of Object.values(revealedSplits(sched, t))) {
				expect(v!).toBeLessThanOrEqual(t);
			}
		}
	});

	it('reveals everything once the pace has finished', () => {
		expect(revealedSplits(sched, paceFinishMs(sched))).toEqual(sched);
	});

	it('is empty for a nonsense clock rather than leaking the lot', () => {
		// A negative or NaN elapsed time is a bug somewhere upstream. The
		// safe failure is showing nothing, never showing everything.
		expect(revealedSplits(sched, -1)).toEqual({});
		expect(revealedSplits(sched, NaN)).toEqual({});
	});
});
