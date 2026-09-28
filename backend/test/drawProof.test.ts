import { createHash, randomUUID } from 'node:crypto';
import {
	type DrawCandidate,
	commitDraw,
	deriveDrawOrder,
	derivedPicker,
	randomPicker,
	seasonCommitment,
} from '../lambda/lib/drawProof';

const SECRET = 'a'.repeat(64);
const OTHER_SECRET = 'b'.repeat(64);

function pool(spec: Record<string, number>): DrawCandidate[] {
	const out: DrawCandidate[] = [];
	for (const [seedType, n] of Object.entries(spec)) {
		for (let i = 0; i < n; i++) {
			out.push({ seedType, seedPairId: `${seedType}-${String(i).padStart(3, '0')}` });
		}
	}
	return out;
}

/**
 * The property the whole scheme rests on: someone holding only the
 * secret, the match id and the candidate list gets the same answer the
 * server got, without having been there.
 */
describe('deriveDrawOrder - reproducibility', () => {
	it('is the same draw every time for the same inputs', () => {
		const candidates = pool({ village: 9, shipwreck: 4, ruined_portal: 7 });
		const a = deriveDrawOrder(candidates, derivedPicker(SECRET, 'match-1'));
		const b = deriveDrawOrder(candidates, derivedPicker(SECRET, 'match-1'));
		expect(a).toEqual(b);
	});

	it('does not depend on the order the candidates arrived in', () => {
		// THE ONE THAT MATTERS. Candidates come out of a DynamoDB Scan,
		// whose order is arbitrary and unreconstructable by an auditor.
		// If the derivation were sensitive to it, every verification
		// would fail for a reason nobody could diagnose - or worse,
		// pass only for whoever happened to scan the same way.
		const candidates = pool({ village: 9, shipwreck: 4, ruined_portal: 7 });
		const shuffled = candidates.slice().reverse();
		expect(deriveDrawOrder(shuffled, derivedPicker(SECRET, 'match-1')))
			.toEqual(deriveDrawOrder(candidates, derivedPicker(SECRET, 'match-1')));
	});

	it('gives a different draw for a different match', () => {
		const candidates = pool({ village: 20, shipwreck: 20 });
		const a = deriveDrawOrder(candidates, derivedPicker(SECRET, 'match-1'));
		const b = deriveDrawOrder(candidates, derivedPicker(SECRET, 'match-2'));
		expect(a[0]).not.toEqual(b[0]);
	});

	it('gives a different draw under a different secret', () => {
		// Which is what makes publishing the commitment mean anything:
		// a swapped secret does not reproduce the season that was played.
		const candidates = pool({ village: 20, shipwreck: 20 });
		const a = deriveDrawOrder(candidates, derivedPicker(SECRET, 'match-1'));
		const b = deriveDrawOrder(candidates, derivedPicker(OTHER_SECRET, 'match-1'));
		expect(a[0]).not.toEqual(b[0]);
	});

	it('returns every candidate exactly once', () => {
		// The order is also the fallback order, so losing a candidate
		// here would silently shrink the pool for that match.
		const candidates = pool({ village: 9, shipwreck: 4, ruined_portal: 7 });
		const order = deriveDrawOrder(candidates, derivedPicker(SECRET, 'm'));
		expect(order.length).toBe(candidates.length);
		expect(new Set(order).size).toBe(candidates.length);
	});

	it('handles a pool of one without dividing by anything', () => {
		expect(deriveDrawOrder(pool({ village: 1 }), derivedPicker(SECRET, 'm')))
			.toEqual(['village:village-000']);
		expect(deriveDrawOrder([], derivedPicker(SECRET, 'm'))).toEqual([]);
	});
});

describe('deriveDrawOrder - fairness of the draw itself', () => {
	it('gives a thin type the same chance as a deep one', () => {
		// Type first, then seed within it. If this regressed to a flat
		// draw over seeds, the common opening would become whichever
		// type the last pool rebuild happened to over-produce - which
		// has happened before, and is invisible from the outside.
		const candidates = pool({ village: 1, shipwreck: 60 });
		let village = 0;
		for (let i = 0; i < 400; i++) {
			const first = deriveDrawOrder(candidates, derivedPicker(SECRET, `m-${i}`))[0];
			if (first.startsWith('village:')) village++;
		}
		expect(village).toBeGreaterThan(140);
		expect(village).toBeLessThan(260);
	});

	it('spreads across the seeds of a type rather than favouring one', () => {
		const candidates = pool({ village: 8 });
		const seen = new Set<string>();
		for (let i = 0; i < 200; i++) {
			seen.add(deriveDrawOrder(candidates, derivedPicker(SECRET, `m-${i}`))[0]);
		}
		expect(seen.size).toBe(8);
	});

	it('draws a flat distribution out of the picker', () => {
		// Rejection sampling, not a raw modulus. A modulus over a 32-bit
		// word biases toward low indices, and a thumb on the scale in
		// the draw is precisely what this file exists to remove.
		const counts = new Array(7).fill(0);
		for (let i = 0; i < 7000; i++) {
			counts[derivedPicker(SECRET, `m-${i}`)(7)]++;
		}
		for (const c of counts) {
			expect(c).toBeGreaterThan(850);
			expect(c).toBeLessThan(1150);
		}
	});

	it('still draws when no season is configured', () => {
		// The unverifiable fallback has to produce a usable draw - a
		// missing season file must not stop the ladder, it must only
		// stop the ladder claiming the draw can be checked.
		const candidates = pool({ village: 5, shipwreck: 5 });
		const order = deriveDrawOrder(candidates, randomPicker());
		expect(new Set(order).size).toBe(10);
	});
});

describe('commitDraw', () => {
	const pair = { seedPairId: 'abc', overworldSeed: 111, netherSeed: 222 };

	it('is stable for the same inputs', () => {
		expect(commitDraw('s1', 'm1', 'nonce', pair))
			.toBe(commitDraw('s1', 'm1', 'nonce', pair));
	});

	it('moves if any part of what it commits to moves', () => {
		const base = commitDraw('s1', 'm1', 'nonce', pair);
		expect(commitDraw('s2', 'm1', 'nonce', pair)).not.toBe(base);
		expect(commitDraw('s1', 'm2', 'nonce', pair)).not.toBe(base);
		expect(commitDraw('s1', 'm1', 'other', pair)).not.toBe(base);
		expect(commitDraw('s1', 'm1', 'nonce', { ...pair, seedPairId: 'abd' })).not.toBe(base);
		expect(commitDraw('s1', 'm1', 'nonce', { ...pair, overworldSeed: 112 })).not.toBe(base);
		expect(commitDraw('s1', 'm1', 'nonce', { ...pair, netherSeed: 223 })).not.toBe(base);
	});

	it('does not leak the seed to someone who can guess the id', () => {
		// The pool is a few hundred rows. Without the nonce, anyone
		// holding it could hash every id and read a live match's world
		// straight off the commitment.
		const withNonce = commitDraw('s1', 'm1', 'nonce', pair);
		const guessed = createHash('sha256')
			.update(['alt-draw-v1', 's1', 'm1', '', 'abc', '111', '222'].join('|'))
			.digest('hex');
		expect(withNonce).not.toBe(guessed);
	});
});

describe('seasonCommitment', () => {
	it('is the SHA256 of the secret, which is what gets published', () => {
		expect(seasonCommitment(SECRET)).toBe(
			createHash('sha256').update(Buffer.from(SECRET, 'hex')).digest('hex'));
	});

	it('does not reveal the secret it commits to', () => {
		expect(seasonCommitment(SECRET)).not.toContain(SECRET);
		expect(seasonCommitment(SECRET)).not.toBe(seasonCommitment(OTHER_SECRET));
	});
});

/**
 * The audit, end to end: a server draws and commits, an auditor who was
 * not there reproduces both from what was recorded. This is the claim
 * the project makes to players, so it is tested as one flow rather than
 * only in parts.
 */
describe('a season, replayed by someone who was not there', () => {
	it('reproduces every draw and opens every commitment', () => {
		const candidates = pool({ village: 12, shipwreck: 9, ruined_portal: 6 });
		const played = [];
		for (let i = 0; i < 25; i++) {
			const matchId = randomUUID();
			const order = deriveDrawOrder(candidates, derivedPicker(SECRET, matchId));
			const [seedType, seedPairId] = [order[0].split(':')[0], order[0].split(':')[1]];
			const pair = { seedPairId, overworldSeed: 1000 + i, netherSeed: 2000 + i };
			played.push({
				matchId,
				seedType,
				pair,
				nonce: `nonce-${i}`,
				commitment: commitDraw('s1', matchId, `nonce-${i}`, pair),
				candidates: order.slice().sort(),
			});
		}

		for (const m of played) {
			const order = deriveDrawOrder(
				m.candidates.map((k) => ({
					seedType: k.slice(0, k.indexOf(':')),
					seedPairId: k.slice(k.indexOf(':') + 1),
				})),
				derivedPicker(SECRET, m.matchId),
			);
			expect(order[0]).toBe(`${m.seedType}:${m.pair.seedPairId}`);
			expect(commitDraw('s1', m.matchId, m.nonce, m.pair)).toBe(m.commitment);
		}
	});

	it('catches a match whose seed was swapped after the fact', () => {
		const matchId = randomUUID();
		const pair = { seedPairId: 'dealt', overworldSeed: 5, netherSeed: 6 };
		const commitment = commitDraw('s1', matchId, 'n', pair);
		const swapped = { ...pair, seedPairId: 'better-one' };
		expect(commitDraw('s1', matchId, 'n', swapped)).not.toBe(commitment);
	});
});
