// What we are willing to store from a client's mod report.
//
// /auth/verify is public, and this field arrives before Mojang has said
// who the caller is, so the body is a stranger's input. The cases worth
// pinning are the two that mean different things - an ABSENT list (no
// client ever looked) against an EMPTY one (it looked and found nothing
// beyond the pack) - because the detail screen shows them differently
// and collapsing them would report no evidence as exculpatory evidence.
import { sanitizeModList } from '../lambda/verifySession';

describe('sanitizeModList', () => {
	it('distinguishes absent from empty', () => {
		// Not recorded: an older client, or one that sent nothing.
		expect(sanitizeModList(undefined)).toBeUndefined();
		expect(sanitizeModList(null)).toBeUndefined();
		// Recorded, and there was nothing to record.
		expect(sanitizeModList([])).toEqual([]);
	});

	it('ignores a body that is not a list', () => {
		expect(sanitizeModList('sodium@0.2.0')).toBeUndefined();
		expect(sanitizeModList({ sodium: '0.2.0' })).toBeUndefined();
		expect(sanitizeModList(7)).toBeUndefined();
	});

	it('sorts, so the same mods produce the same list', () => {
		// Fabric's load order is not stable between launches, and the
		// point of recording this is comparison.
		expect(sanitizeModList(['sodium@0.2.0', 'lithium@0.7.0']))
			.toEqual(['lithium@0.7.0', 'sodium@0.2.0']);
	});

	it('deduplicates', () => {
		expect(sanitizeModList(['sodium@0.2.0', 'sodium@0.2.0'])).toEqual(['sodium@0.2.0']);
	});

	it('drops entries that are not usable strings', () => {
		expect(sanitizeModList(['sodium@0.2.0', 42, null, '', '   ', {}]))
			.toEqual(['sodium@0.2.0']);
	});

	it('truncates an overlong entry rather than refusing the list', () => {
		const long = 'x'.repeat(500);
		const out = sanitizeModList([long])!;
		expect(out).toHaveLength(1);
		expect(out[0].length).toBe(80);
	});

	it('caps how many entries it will keep', () => {
		const many = Array.from({ length: 5000 }, (_, i) => `mod${String(i).padStart(5, '0')}@1`);
		expect(sanitizeModList(many)!.length).toBeLessThanOrEqual(200);
	});

	it('keeps a realistic pack intact', () => {
		// A pack list is a few dozen entries once Fabric API's modules
		// are counted, which must come nowhere near the cap.
		const pack = ['minecraft@1.16.1', 'java@17', 'fabricloader@0.19.5', 'speedrunmcalt@0.1.0']
			.concat(Array.from({ length: 40 }, (_, i) => `fabric-module-${i}@1.0.0`));
		expect(sanitizeModList(pack)!.length).toBe(pack.length);
	});
});
