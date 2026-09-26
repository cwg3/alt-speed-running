import { scoreMatch } from '../lambda/lib/matchCompletion';
import { isSyntheticPlayer } from '../lambda/lib/seedPool';

/**
 * The exhibition rule is the only thing standing between a summonable
 * bot and a rating farm, and it had no test at all while it was also the
 * only safeguard. ~180 synthetic matches had to be undone by
 * resetLadder.ts because nothing here said no.
 */
describe('scoreMatch - exhibition', () => {
	it('scores nothing at all when a synthetic player is involved', () => {
		expect(scoreMatch(1500, 1500, true)).toEqual({
			winnerDelta: 0, loserDelta: 0, seasonPoints: 0,
		});
	});

	it('withholds season points too, not just Elo', () => {
		// The earlier shape floored season points at 1, so an exhibition
		// that only zeroed the Elo would still have paid out a point per
		// race - a slow farm is still a farm.
		const { seasonPoints } = scoreMatch(1200, 1900, true);
		expect(seasonPoints).toBe(0);
	});

	it('is unaffected by which side is stronger', () => {
		for (const [w, l] of [[1500, 1500], [1000, 2000], [2000, 1000]]) {
			expect(scoreMatch(w, l, true)).toEqual({
				winnerDelta: 0, loserDelta: 0, seasonPoints: 0,
			});
		}
	});

	it('recognises PaceBot as synthetic, so a bot match is an exhibition', () => {
		// Guards the wiring, not the list: if the uuid or the predicate
		// moves, an exhibition silently becomes a rated match.
		expect(isSyntheticPlayer('bot-rival')).toBe(true);
		expect(isSyntheticPlayer('0be0bc343c1647a6b8c47b668dbaa090')).toBe(false);
	});
});

describe('scoreMatch - rated', () => {
	it('gives an even match the full half-K swing', () => {
		expect(scoreMatch(1500, 1500, false)).toEqual({
			winnerDelta: 16, loserDelta: -16, seasonPoints: 10,
		});
	});

	it('is zero-sum on Elo', () => {
		for (const [w, l] of [[1500, 1500], [1200, 1800], [1800, 1200]]) {
			const s = scoreMatch(w, l, false);
			expect(s.winnerDelta + s.loserDelta).toBe(0);
		}
	});

	it('pays an underdog more than a favourite', () => {
		const underdog = scoreMatch(1200, 1800, false);
		const favourite = scoreMatch(1800, 1200, false);
		expect(underdog.winnerDelta).toBeGreaterThan(favourite.winnerDelta);
		expect(underdog.seasonPoints).toBeGreaterThan(favourite.seasonPoints);
	});

	it('floors season points at 1 for beating a much weaker opponent', () => {
		// Beating someone 600 below would otherwise compute negative, and
		// a win that costs public points is worse than one worth nothing.
		expect(scoreMatch(2100, 1500, false).seasonPoints).toBe(1);
	});

	it('never awards a rated win zero Elo by accident', () => {
		// A 0 delta is how an exhibition looks, so a rated match must not
		// produce one - otherwise the stored exhibition flag and the
		// deltas could disagree about what happened.
		expect(scoreMatch(1500, 1500, false).winnerDelta).not.toBe(0);
	});
});
