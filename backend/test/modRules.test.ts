// The whitelist as the queue-join check reads it.
//
// Two failure shapes are worth pinning, because each one fails a
// different person:
//
//   too strict - a legal mod, or a Fabric API module, refused. That
//                turns an honest player away from a ladder they are
//                complying with, and they cannot tell why.
//   too loose  - the "allow anything starting fabric-" shortcut, which
//                waves through a mod that took the name.
//
// The second is why PACK_MODULES is generated from the pinned jar rather
// than matched by a prefix. Both are cheap to test and neither is
// visible in play until somebody is wrongly refused or wrongly allowed.
import { illegalMods, modId, ALLOWED_MODS, PACK_MODULES } from '../lambda/lib/modRules';

describe('modId', () => {
	it('takes the id half of an id@version entry', () => {
		expect(modId('sodium@0.2.0')).toBe('sodium');
		// A Fabric version string carries + and -, never an @.
		expect(modId('fabric-api-base@0.3.0+a02b446313')).toBe('fabric-api-base');
	});

	it('tolerates an entry with no version', () => {
		expect(modId('sodium')).toBe('sodium');
	});

	it('normalises case and surrounding space', () => {
		expect(modId(' Sodium@0.2.0 ')).toBe('sodium');
	});
});

describe('illegalMods', () => {
	it('judges nothing when no list was reported', () => {
		// A client from before the recording existed. Refusing it would
		// break the promise 0.1.1's notes make, and there is nothing to
		// judge in any case.
		expect(illegalMods(undefined)).toEqual([]);
	});

	it('allows a client that reported an empty list', () => {
		expect(illegalMods([])).toEqual([]);
	});

	it('allows the pack', () => {
		const pack = [
			'minecraft@1.16.1', 'java@17', 'fabricloader@0.19.5',
			'speedrunmcalt@0.1.1', 'fabric@0.18.0+build.387-1.16.1',
			'fabric-api-base@0.3.0', 'fabric-rendering-v1@1.5.0',
		];
		expect(illegalMods(pack)).toEqual([]);
	});

	it('allows every module of the pinned Fabric API build', () => {
		// The generated list is the thing that makes the prefix rule
		// unnecessary, so it has to actually cover the jar.
		expect(PACK_MODULES.length).toBeGreaterThan(30);
		expect(illegalMods(PACK_MODULES.map((id) => `${id}@1.0.0`))).toEqual([]);
	});

	it('allows the whitelisted mods at any version', () => {
		expect(illegalMods(ALLOWED_MODS.map((id) => `${id}@9.9.9`))).toEqual([]);
		expect(illegalMods(['sodium@0.1.0'])).toEqual([]);
		expect(illegalMods(['sodium@0.2.0'])).toEqual([]);
	});

	it('flags a mod nobody listed', () => {
		expect(illegalMods(['autofish@1.0.0', 'sodium@0.2.0']))
			.toEqual(['autofish@1.0.0']);
	});

	it('flags a mod that only LOOKS like a Fabric module', () => {
		// The hole a "starts with fabric-" rule would leave open, and the
		// reason the module list is read out of the jar.
		expect(illegalMods(['fabric-xray-v1@1.0.0'])).toEqual(['fabric-xray-v1@1.0.0']);
	});

	it('returns the entry, not just the id, so a refusal can name a version', () => {
		expect(illegalMods(['seedcracker@2.0.0'])).toEqual(['seedcracker@2.0.0']);
	});

	it('does not yet allow MCSR Fairplay, and that is load-bearing', () => {
		// README calls it legal; its real id has not been read off an
		// install. This test exists to fail LOUDLY when somebody adds a
		// guessed id, and to be updated in the same commit that confirms
		// the real one. Enabling the gate before then refuses a mod the
		// rules permit.
		expect(ALLOWED_MODS).not.toContain('fairplay');
		expect(ALLOWED_MODS).not.toContain('mcsrfairplay');
		expect(ALLOWED_MODS).not.toContain('mcsr-fairplay');
	});
});
