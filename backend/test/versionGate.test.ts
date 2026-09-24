// The comparison the version gate turns on.
//
// Written as a test because the failure it guards against is silent:
// a lexicographic compare says "0.10.0" < "0.9.0", so the gate would
// lock out the NEWER client and let the older one through, and
// nothing about that is visible until someone releases a tenth patch.
import { versionAtLeast } from '../lambda/verifySession';

describe('versionAtLeast', () => {
	it('accepts an exact match', () => {
		expect(versionAtLeast('0.1.0', '0.1.0')).toBe(true);
	});

	it('accepts a newer client', () => {
		expect(versionAtLeast('0.2.0', '0.1.0')).toBe(true);
		expect(versionAtLeast('1.0.0', '0.9.9')).toBe(true);
	});

	it('rejects an older client', () => {
		expect(versionAtLeast('0.0.9', '0.1.0')).toBe(false);
	});

	it('compares numerically, not as strings', () => {
		// The whole reason this is a function and not a < comparison.
		expect(versionAtLeast('0.10.0', '0.9.0')).toBe(true);
		expect(versionAtLeast('0.9.0', '0.10.0')).toBe(false);
	});

	it('ignores build and prerelease suffixes', () => {
		expect(versionAtLeast('0.1.0+build.7', '0.1.0')).toBe(true);
		expect(versionAtLeast('0.2.0-rc1', '0.1.0')).toBe(true);
	});

	it('treats a missing or unparseable version as too old', () => {
		expect(versionAtLeast('0.0.0', '0.1.0')).toBe(false);
		expect(versionAtLeast('unknown', '0.1.0')).toBe(false);
	});

	it('treats absent trailing parts as zero', () => {
		expect(versionAtLeast('0.1', '0.1.0')).toBe(true);
		expect(versionAtLeast('0.1', '0.1.1')).toBe(false);
	});
});
