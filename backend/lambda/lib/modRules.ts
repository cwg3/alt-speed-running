// The published mod whitelist, server side.
//
// The rule is in README.md: a mod is legal if it changes how the game is
// DRAWN or how fast it runs, and not legal if it changes game state,
// reveals what the seed did not give you, or automates input. This file
// is that rule as data, and it is the only place the list lives on the
// backend - a second copy is a second answer.
//
// WHAT THIS IS NOT. It cannot stop a cheater. Anyone able to patch the
// client can patch what it reports, signature or no signature. What a
// check here stops is the HONEST MISTAKE - somebody who installed a
// performance mod for the framerate and never read a rules page - and
// that is most violations. Deliberate cheating is caught by the position
// timeline against the split record, with a human looking. See SPEC.md.

/**
 * Ids installed by the pack itself, read out of the pinned Fabric API
 * jar by `tools/pack-modules.sh`.
 *
 * Generated rather than matched by a rule. The client can ask the loader
 * which modules are nested inside Fabric API; a Lambda sees a flat list
 * of ids, and the tempting shortcut - allow anything starting "fabric-"
 * - would wave through a mod called fabric-anything. These are the real
 * ids, so there is no such hole.
 *
 * TIED TO THE PINNED BUILD. Re-run the script when fabric_api_version
 * changes, or an upstream rename will look like an unknown mod.
 */
export const PACK_MODULES: readonly string[] = [
	'fabric',
	'fabric-api-base',
	'fabric-biomes-v1',
	'fabric-blockrenderlayer-v1',
	'fabric-command-api-v1',
	'fabric-commands-v0',
	'fabric-containers-v0',
	'fabric-content-registries-v0',
	'fabric-crash-report-info-v1',
	'fabric-dimensions-v1',
	'fabric-events-interaction-v0',
	'fabric-events-lifecycle-v0',
	'fabric-game-rule-api-v1',
	'fabric-item-api-v1',
	'fabric-item-groups-v0',
	'fabric-key-binding-api-v1',
	'fabric-keybindings-v0',
	'fabric-lifecycle-events-v1',
	'fabric-loot-tables-v1',
	'fabric-mining-levels-v0',
	'fabric-models-v0',
	'fabric-networking-blockentity-v0',
	'fabric-networking-v0',
	'fabric-object-builder-api-v1',
	'fabric-object-builders-v0',
	'fabric-particles-v1',
	'fabric-registry-sync-v0',
	'fabric-renderer-api-v1',
	'fabric-renderer-indigo',
	'fabric-renderer-registries-v1',
	'fabric-rendering-data-attachment-v1',
	'fabric-rendering-fluids-v1',
	'fabric-rendering-v0',
	'fabric-rendering-v1',
	'fabric-resource-loader-v0',
	'fabric-screen-handler-api-v1',
	'fabric-tag-extensions-v0',
	'fabric-textures-v0',
	'fabric-tool-attribute-api-v1',
];

/** Present on every install, and not from the Fabric API jar. */
export const PACK_ROOTS: readonly string[] = [
	'minecraft',
	'java',
	'fabricloader',
	'speedrunmcalt',
];

/**
 * Third-party mods the whitelist allows, by id.
 *
 * Ids, not versions. Pinning a version would refuse a legal mod the day
 * it updates, and the reason these are allowed - they change rendering
 * or performance, not the game - does not change between their versions.
 *
 * MCSR FAIRPLAY IS MISSING, DELIBERATELY. README names it as legal, but
 * its mod id has not been read off a real install, and an allowlist
 * entry that never matches is worse than an absent one: it refuses a mod
 * the rules permit, and it does it silently. Confirming that id is a
 * precondition for turning the gate on - see TODO.md.
 */
export const ALLOWED_MODS: readonly string[] = [
	'sodium',
	'lithium',
	'starlight',
	'speedrunigt',
];

/** The id half of an "id@version" entry, lowercased. */
export function modId(entry: string): string {
	const at = entry.indexOf('@');
	return (at < 0 ? entry : entry.slice(0, at)).trim().toLowerCase();
}

/**
 * The entries of `mods` that the rules do not account for.
 *
 * An UNDEFINED list returns nothing to answer, and that is deliberate:
 * a client that reported no list cannot be judged on one. Clients from
 * before the recording existed are still allowed to queue, which is the
 * promise 0.1.1's release notes make. It is the same instinct as the
 * split rules - absent data degrades to loose, never to strict - and it
 * costs little, because the gate only ever stops honest mistakes and an
 * honest client is the one that reports.
 */
export function illegalMods(mods: readonly string[] | undefined): string[] {
	if (!mods) return [];
	const allowed = new Set<string>([...PACK_MODULES, ...PACK_ROOTS, ...ALLOWED_MODS]);
	return mods.filter((entry) => !allowed.has(modId(entry)));
}
