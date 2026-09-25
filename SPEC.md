# Match guarantees

What `alt` promises about a match world, what it actually does today,
and what it does not.

This is the honest version. Anything marked **not built** is a promise
the code does not currently keep — worth knowing before a stranger
plays, because most of these were found only when a player hit them in
a live match.

**Some guarantees cannot be self-tested, and that is a third
category.** "Never fired in play" means nobody has reached it yet. But
a few promises are NEGATIVE or STATISTICAL, and no amount of playing
by one person confirms them:

- *Drowned never spawn holding a trident.* Not seeing an armed drowned
  in a handful of runs is not evidence; tridents were always uncommon.
- *Wither skeleton hordes never choke a corridor.* Choke hordes are
  rare to begin with, and the thresholds here are invented rather than
  measured.

Both need many runs by people who know what the unmodified game feels
like and would notice the absence. They are marked **needs runners**
rather than left in the untested pile, because one more session of
solo testing will not move them.

**"Never fired in play" is its own category.** Those guarantees are
written, compiled, and verified in a harness, but no real run has
reached them. Today's record says that is not the same as working: a
barter mixin that compiled and passed every check crashed the server
thread at the first piglin, because mixin signatures are validated when
the target class loads, not when the code builds.

## The trade

Match worlds are **not pure vanilla for their seed**. Some guarantees
are found by filtering seeds; others are placed into the world after
generation. The alternative — filter only — cannot reach comparable
guarantees at any affordable cost, and a ladder meant to rank the best
players needs runs that are comparable to each other.

Every modification is listed here. That is the point: the objection to
the incumbent is opacity, so anything we change has to be stated.

Rows marked **[ours]** are deliberate departures from the established
standard rather than implementations of it. They are choices, and they
should be argued for rather than assumed.

They are also **provisional**. They were set from first principles plus
one runner's judgement, which is enough to build on and not enough to
be confident about. Each should be put in front of experienced runners
and revised on what they say, not defended because it is already in the
code. Current departures:

| | ours | standard |
|---|---|---|
| ruined portal iron | 27 nuggets (a full bucket) | 18 nuggets |
| ruined portal obsidian | 2-4, varying by seed | unspecified |
| ruined portal route | lava and water always available | 80/20 obsidian vs bucket |
| seed type mix | even, 40 of each | unspecified |
| wither skeleton crowding | max 4 within 12 blocks | unspecified |
| nether arrival pad | built when <10% of nearby ground is standable | unspecified |

The even type mix is the one most likely to be wrong. It means a
quarter of matches open on buried treasure, which needs a technique
not every runner has. That is either the ladder testing range, or it is
a quarter of matches decided before anyone reaches the nether -
and which of those it is cannot be settled from a spreadsheet.

**Determinism is the hard constraint.** Both players race the same
seed and must receive byte-identical worlds. Every placement derives
from the match seed alone — never wall-clock, client state, or
iteration order over an unordered collection. A difference would void
the match and would do it intermittently.

## Overworld

### Seed types

**Every spawn is runnable.** Vanilla can drop a player into open ocean
or empty desert with no route at all; a filtered pool exists so that
never happens and neither player loses to the spawn itself.

A seed is good for exactly one opening route, never all of them.
Requiring a village *and* a shipwreck *and* a desert temple near spawn
drives the pass rate to nothing, so seeds are classified into one type
and pooled separately.

| Type | Distance from spawn | Status |
|---|---|---|
| Village | ≤ 7 chunks | built |
| Desert temple | ≤ 5 chunks | built |
| Ruined portal | ≤ 3 chunks | built |
| Shipwreck | ≤ 4 chunks | built |
| Buried treasure | ≤ 5 chunks | built |

Distance is measured from the **actual world spawn**, not the origin.
Minecraft searches outward for a valid spawn biome and routinely lands
200–350 blocks from (0,0); measuring from the origin produced seeds
whose "16 blocks from spawn" village was really 328 blocks away.

**A wood source within 5 chunks of spawn — built (filter).** Reported
from play: a desert temple seed with no tree within four or five
chunks, which is unroutable rather than merely awkward — a desert
village has oak buildings to fall back on, a temple has sandstone.

The cubiomes stage is a **biome** check, which is a pre-filter and
nothing more. It asks what biome each sampled column sits in, walking a
5-chunk radius from world spawn in 16-block steps, and passes on a
single wooded hit.

That is not a tree. It shipped a shipwreck seed whose spawn was open
ocean with a jungle biome clipping the sample radius, where the only
logs within 80 blocks were the WRECK'S OWN HULL, 61 blocks out and 12
blocks under water. The player had no wood, so no crafting table, so no
run.

So a generated-world stage decides it now: `SpawnResourceHook` counts
real `*_log` blocks within 128 blocks of spawn, above y60, ignoring
anything with water above it. Submerged wood is a wreck, not a tree.
Measured verdicts - that ocean seed scores 0, a working village 334, a
desert temple whose nearest tree is 83 blocks away 85.

Two false negatives had to be fixed before it could be trusted, both
caught by checking its rejections against an independent count rather
than believing them. It excluded the structure's own bounding box, and
a village box is about 107 by 174 blocks, so it swallowed every tree
near the village AND the village's own logs - reporting 0 for a seed
with 235 within 80 blocks. And its radius was 80, inherited from the
cubiomes check without asking whether it was right, which rejected a
desert temple whose nearest tree was 83 blocks out with 470 inside 160.

The biome list is deliberately conservative, so the error runs toward
**discarding good seeds rather than shipping unroutable ones**. It
costs candidates — the filter reports how many matches it rejects on
this rule, so a list or radius that has gone wrong is visible rather
than silent.

The honest version counts real logs in the generated world. That costs
a world generation per candidate and would need the chunk-local
treatment `ContainerScan` got, since the naive block scan is what
killed the launcher's x86 JVM under Rosetta. Worth doing; it was not
worth blocking this on.

### Per type

| Guarantee | Status |
|---|---|
| Village: blacksmith present | built — every village seed is verified against a generated world, because the jigsaw claiming a smith piece does not mean a smith chest generated |
| Village: 3 iron in the smith's chest (+4 from the golem = 7) | built |
| Village: **4 iron + 3 diamonds** as an alternative to the 7-iron threshold | built (verification) — the live pool predates it, see below |
| Village: seed ships the blacksmith's position, not the village anchor | built |
| Village: 3 lava pools ~2 chunks out | built (placed) |
| Village + desert temple: river within 6 chunks (boat routing) | built (filter) |
| Village: all five biome variants eligible (plains, desert, savanna, taiga, snowy) | built (filter) — the earlier taiga/snowy exclusion was **removed**, see below |
| Desert temple: 7 iron, 52 hunger points of food | built — was "13 rotten flesh"; counted in items until a player ran short |
| Desert temple: string (→ wool → beds) and sand (→ out of the chest pit) | **not built** — both are route material, neither is guaranteed |
| Desert temple: 3 lava pools | built (placed) |
| Shipwreck / buried treasure: 7 iron equivalent, 88 hunger points of food | built — 88 is the measured average of eleven the incumbent supply chests |
| Shipwreck / buried treasure: 2 magma ravines within 10 chunks, with bubble columns and kelp | built (world check) — matches the standard |
| Ruined portal: 27 nuggets (= 3 ingots = one bucket) | built — **[ours]**, the standard is 18 nuggets |
| Ruined portal: 2–4 obsidian, seed-varied | built — **[ours]**, not in the standard |
| Ruined portal: light source | built |
| Ruined portal: lava + water for the bucket route | built (placed) — **[ours]**; the standard splits obsidian/bucket 80/20 |
| Ruined portal: above ground, not submerged, actually exists | built — checked at world creation, portal placed when vanilla's fails |
| Unbroken shipwrecks: exactly 3 chests (supply, treasure, map) | built (placed) — verified against a real half-wreck that had 1 of 3 |
| Shipwreck is reachable, not entombed in terrain | **not built** — found by a player vote, see below |
| Flint drops mirrored between players (same gravel-break count) | built (2 per 20 breaks = vanilla 10%, flint guaranteed within 10) — never fired in play |
| Villager trades locked and identical between worlds | **out of scope** — see below |
| Drowned never spawn holding a trident | built — **needs runners**, not self-testable, see below |
| No hostile mobs or bats inside desert temples (keeps pie-ray clean) | built — but see below, reported broken in play twice |
| Suspicious stew never applies a harmful effect | built — never fired in play |
| Craftable soups never poisonous | satisfied by vanilla — mushroom stew, rabbit stew and beetroot soup carry no effects at all |
| Food guarantees count only food worth eating | built — rotten flesh counts at a desert temple and nowhere else |
| Overworld structures stripped of entity noise that pollutes pie-ray | **not built — TBD**, scope undecided, see below |

**Entity noise and pie-ray: not built, and deliberately left open.**
The desert temple case is done — hostile mobs and bats are kept out of
the temple's own box. What "other overworld structures" should mean is
not settled, and the readings differ enough to matter:

- Extending the same mob suppression to more structures is easy, but
  for a **village** it stops being noise removal and becomes a
  difficulty change: no zombies in the village is an advantage, not a
  cleaner F3 chart.
- Stripping non-mob decorative entities — item frames, armour stands,
  paintings, boats — is balance-neutral, but 1.16 overworld structures
  carry very few of them, so the real effect may be near zero.

Left TBD rather than guessed at. It should be settled by someone who
actually pie-rays, and the deciding question is which structure's
entity load has thrown off a real reading.

**Shipwrecks are not checked for reachability.** Reported by a
bad-seed vote — "shipwreck buried above ground - didn't see any
chests" — on seed#ed30, structure 192,160.

All three chests existed and the normalisation correctly reported the
wreck complete. They were at y62, y61 and y59, spread over sixteen
blocks of x, at or below sea level with terrain on top.
`ContainerScan` finds them because it reads chunk block-entity maps
and does not care what is above them. The player cannot, because
something is.

This is the ruined portal rule with a different structure: *at least
partly above ground, not entombed*. RP gets that check because two
thirds of those seeds fail it; shipwrecks never got one, and vanilla
buries them in seabed and beach quite happily.

The fix mirrors `PortalVerify`: confirm at least one chest has a clear
vertical path to open water or air. It needs the world generated, so
it belongs in tier 3 alongside the magma ravine check that ocean seeds
already pay for — which means it is close to free to add.

**First bad seed found by the vote rather than by us.** That is the
mechanism working as designed: a player hit something no test covered,
said so in one line, and the line named the bug.

**Villager trades are deliberately NOT normalised.** No competent
runner trades with a villager the way a survival player does — the
route does not pass through it — so locking trades would be work spent
on a path nobody takes. Dropped rather than deferred: if this ever
comes back it should be because a runner said it mattered, not because
it was left on a list.

**Villages: filtered by contents, not by biome.** All five vanilla
village types are eligible. A village qualifies on what it holds:

- a blacksmith — weaponsmith, toolsmith or armorer — with enough to
  progress;
- an iron threshold of 7 from structures plus the iron golem, **or**
  4 iron and 3 diamonds;
- a river biome and usable lava pools nearby (or enough obsidian in the
  blacksmith chest to enter the nether).

The **4 iron + 3 diamonds** branch is modelled. `VillageLoot` counts
diamonds alongside iron, and `verify-villages.sh` accepts a village on
either route: 7 iron from chests plus the golem's 4, or 4 iron and 3
diamonds.

The rule lives in exactly one place, and the verification writes out
the seeds that pass it (`mod/run/village-qualified.txt`). The first
time villages were selected, the rule was applied by hand on smith
presence alone — which silently ignored the resource threshold
entirely, and would have ignored the diamond branch too.

**The live pool predates this.** Its 29 villages were chosen on "has a
real smith chest", so every one is verified playable, but none was
checked against either resource threshold. Re-verifying them is about
seven minutes of world generation and has not been done.

This replaces an exclusion of taiga and snowy villages on the grounds
that they were the low-resource variants. That was the wrong
mechanism: the standard constrains what a village CONTAINS, not which
biome it sits in, and a cold village meeting the requirements is a
legitimate seed. The exclusion also did not work — it sampled the
biome at block resolution at the village position while the game picks
the village variant from the quarter-scale noise grid at the chunk
centre, so a taiga village reached a live match anyway.

**Blacksmith: a piece name is not a chest.** The check asked whether
the predicted structure contained a jigsaw piece whose name contained
`armorer`, `weaponsmith` or `toolsmith`. A taiga village satisfied that
and generated no smith chest at all: every container in it was
`village_taiga_house` or an untagged workstation barrel. The iron
guarantee landed in an ordinary house chest roughly a hundred blocks
from the coordinate the player was given.

Verification now generates the village and reads the real loot tables
(`verify-villages.sh`, driving `LootVerifyHook`). It costs a world
generation per candidate, which is why the cheap proxy existed. Two
smithless villages reaching live matches settled that trade.

Same failure as everywhere else in this document: **a proxy was
checked instead of the thing itself.**

**Temple mobs: the box was in the wrong place, twice.** The suppression
mixin was correct from the start — it cancels natural and
chunk-generation spawns for hostiles and bats inside the temple. What
was wrong is where it thought the temple was.

`MatchState.templeBox` was set from the *predicted* structure box. That
prediction is exact in X and Z and unreliable in Y: one measured temple
was predicted at y[64..78] with its chests at y53. So the mixin tested
every mob against a box floating above the structure, matched nothing,
and cancelled nothing. It reported success and changed the game not at
all — which is why it was called fixed twice while a player kept
finding mobs.

The box is now built from the chests the loot pass actually found —
X and Z from the prediction, which are trustworthy, and Y from real
block entities. Loot top-up was never affected by this because
`ContainerScan` reads whole chunk block-entity maps and ignores Y.

Confirmed in play. A live desert temple reported `Temple quiet zone
y[49..77] (chests at y53, predicted y[64..78])` — the predicted box
started eleven blocks ABOVE the chests, which is precisely why the
mixin had been cancelling nothing while reporting success.

### Ruined portal

**Ruined portal seeds are FILTERED, not built.** A match world contains
the seed's own vanilla ruined portal, unmodified - real debris, real
magma, real gold blocks - and the pool only admits seeds whose portal a
runner can actually finish.

The standard is measured off the incumbent, not invented. Three ruined
portal worlds read out of an the incumbent install, with the same seeds
regenerated unmodified for comparison: **0 blocks differ**, so they
ship vanilla and filter for it. All three have the same shape:

    a vertical frame, 4 wide by 5 tall, of real obsidian,
    exactly 2 non-corner blocks missing, and a chest.

**A shipwreck needs its SUPPLY and TREASURE chests; the map chest does
not matter.** Food, and iron and diamonds, are what the route uses. The
map chest holds paper, feathers and a filled map - nothing a runner
opens - so requiring all three rejected a wreck over a chest nobody
touches, and a block sitting on that chest failed a seed that was
otherwise perfect.

The rule cuts both ways. It reinstated one seed whose only blocked
chest was the map, and it rejected two wrecks that had ONLY a supply
chest: food but no iron and no diamonds, which is not a shipwreck
opening.

`PortalFrame` enforces that: **above ground**, vertical, at least 4x5,
at most 2 missing non-corner slots, **no crying obsidian in a frame
slot**, and **at least one chest**. Each of those clauses was learned the hard way, in
play, one death at a time. Crying obsidian in a required slot cannot be filled at all -
clearing it needs a diamond pickaxe - and a portal with no chest can
never be made playable, because the two missing obsidian, the light
source and the iron are all guaranteed INTO that chest.

Above-ground is the one that got away. `PortalVerify` checked it and
`PortalFrame` initially did not - replacing a check kept the half it
got wrong and dropped the half it got right. A correct 4x5 frame with a
chest and two gold blocks, buried at y57 under terrain at y75, was
dealt to a player who stood on top of it and saw grass. Terrain height
comes from the generator's noise, never the heightmap at the portal's
own column: the heightmap counts the portal's obsidian and would call a
fully buried portal above ground.

**Confirmed in play 2026-09-22** on seed#9796, the first
filtered ruined portal ever dealt: the player reported "the RP was
good". Frame top y67 against terrain y64, two obsidian to place, chest
present - and vanilla's own loot roll gave no igniter on that seed, so
the flint and steel came from the top-up, which is the guarantee that
had failed silently the same morning.

The frame check rejects most candidates on its own, and the chest requirement rejects more.
The buried treasure ravine filter runs at 3%, so this is well inside
what the pipeline already tolerates - roughly 5 minutes of build time
per accepted seed, and build time is the cheap resource.

**This replaced two earlier answers in one day, and the reasoning is
worth keeping.** The original rule was "keep vanilla's portal when it
is good, build one otherwise". That decision was made by a check that
counted obsidian without asking whether it formed a frame: all three
seeds it passed were unplayable, and one reached a player as twelve
obsidian lying flat on the ground.

The first fix was to always build. That lasted an hour, until the
question "what does a real ruined portal contain?" was actually
measured: 168 netherrack, 13 magma blocks, a stone-brick debris
palette, and **four gold blocks**. Gold is bartering material, so a
built frame was silently withholding a resource the opening is supposed
to provide - and matching vanilla properly meant reconstructing the
whole structure, to imitate something vanilla already does perfectly.

Filtering costs build time and gets the real thing. That is the trade,
and it only looked unaffordable while the pass rate was a guess.

**Placement can fail, and failure must not ship.** Found in play
2026-09-22: a seed whose vanilla portal was submerged in open ocean.
The submerged check worked exactly as designed; the fallback placer
then found nowhere dry to build - all 256 candidate sites rejected for
no ground - and the race started anyway, on a world with no portal and
no lava pool. The player had no route, and no way to tell our failure
from their own bad luck.

The mod now records what it could not provide in
`MatchState.setupFailure`, logs it at ERROR rather than WARN, and tells
the player in chat that the seed is broken and points them at the
bad-seed vote. That is the one mechanism that ends a match with no
rating change for either side.

The real fix is upstream: **the pool build must run the same placement
the mod runs and reject seeds where it fails.** Filtering for "a portal
is predicted here" and then trusting the runtime to rescue it is the
same shape of mistake as verifying a piece name instead of a chest.
This must be done BEFORE the next pool rebuild, or the rebuild will
re-admit the same class of seed.

**A placed portal's guarantees must be scoped to the portal we
built.** Found in play 2026-09-22, second ruined portal failure of the
day: a player reached a placed portal with flint, obsidian and a golden
pickaxe, and no way to light it.

`ContainerScan.find` takes whole chunks and ignores Y on purpose -
structure boxes are exact in X and Z and have been seen wrong in Y by
fifty blocks. That is right for a predicted structure and wrong for one
we built and therefore measured. The placed portal sat thirteen blocks
from the buried vanilla portal it replaced, so the chunk-wide scan
reached that chest 22 blocks underground, counted its flint and steel
as the player's light source, and posted the player's topped-up iron
into it. Every guarantee was satisfied, in a chest nobody could open.

`ContainerScan.findWithin` honours the box's Y, and the placed-portal
path uses it. On the same seed the surface chest now holds the iron and
a flint and steel, and the buried one is left alone.

Tier 5 now also asserts that a ruined portal can be LIT - an igniter in
a container within 24 blocks of it. "A portal is here" was true and
useless.

**This is the most visible thing the mod builds.** A placed portal is
not at a vanilla-determined location, so "seed X has a portal at Y"
stops being checkable against the game. It stays reproducible from the
published algorithm — verifiable against this spec, not against
Minecraft.

## Nether

Overworld and nether seeds are searched **independently** and paired
afterwards. This deliberately breaks the correlation vanilla has
between the two, which is what Divine Travel depends on.

| Guarantee | Status |
|---|---|
| Independent overworld/nether seeds (Divine Travel broken) | built |
| Bastion unambiguous: nearest, and 10 chunks clearer than any rival | built |
| All four bastion types in the pool, recorded not filtered | built |
| Piglin barters mirrored between players | built — confirmed in a live match |
| Piglin barters: per 72 (8 gold blocks) — exactly 3 pearl trades, 6+ obsidian | built |
| Piglin barters: baseline obsidian rate boosted ~35% above vanilla | built (8 trades per cycle, floor of 6) |
| Bastion within **14 chunks** of nether spawn, and clearly closer than any rival | built (filter) |
| Fortress within **16 chunks of that bastion** | built (filter) |
| Open terrain paths between spawn, bastion and fortress | **not built** |
| Nether arrival is not in Basalt Deltas | built (filter) |
| Nether arrival has usable ground, not an open lava sea | built (checked on arrival, pad placed only when unrunnable) — **[ours]**; never fired in play |
| Blaze rods: pity-capped and mirrored | built (6 per 12 kills, max 2-miss streak) — confirmed in a live match |
| Bastion chests: 3 iron, 5 obsidian, 48–64 string, all four types | built — confirmed live on hoglin stable and bridge |
| Hoglin porkchop drops normalised and mirrored | built — **player-credited kills only**, see below; still never fired in play |
| Wither skeleton overcrowding ("choke" hordes) suppressed | built — **[ours]**, numbers invented; **needs runners**, see below |

**Mirrored drops only apply to kills the game credits to a player.**
Blaze rods, hoglin porkchops and flint all check `causedByPlayer`, and
that is deliberate: the schedule is a QUEUE, and if ambient deaths
consumed slots - a hoglin burning to death across the map, unseen -
the two players' queues would drift apart and the mirroring would be
worthless.

The cost is a real hole. **Fire-cooking hoglins is a legitimate
technique**, and a hoglin the player never damaged is not credited to
them, so those kills fall through to vanilla RNG. A player who cooks
gets vanilla variance; a player who melees gets the schedule. Reported
from play: a hoglin killed with lava dropped vanilla loot and the
schedule never built.

Not obviously fixable without breaking the queue. Widening the test to
"died anywhere near a player" would let distant deaths consume slots
again. Worth putting to runners: does anyone actually cook hoglins
often enough for the variance to matter?

**The nether distance rules, corrected.** The standard is:

- the intended bastion within **14 chunks of nether spawn**, and
  significantly closer to the origin than any competing bastion;
- the fortress within **16 chunks of that bastion** - not of spawn;
- open terrain paths between spawn, bastion and fortress, so nobody is
  walled off behind netherrack.

This file previously measured both structures from spawn at 16 chunks.
That was my own invention, introduced while "fixing" the rule and
justified with the reasoning that a bastion-relative fortress could sit
30 chunks from the origin. The reasoning was wrong: a runner goes
spawn to bastion to fortress, and the leg that matters is the second
one, not the distance back to a point nobody returns to. It was also
strictly stricter, so it discarded seeds the standard accepts.

**The open-path check is not built.** It needs the world generated, so
it belongs in tier 3 - and it is the same class as every other gap
found today: the filter proves a structure EXISTS and says nothing
about whether a player can get to it.

**Nether structures were placed from the WRONG SEED.** This was the
foundational defect, and it sat under everything else for days.

A match world uses two seeds - overworld and nether - so that knowing
one dimension cannot tell you the other. Biomes honoured that. Structures
never did. `ChunkStatus.STRUCTURE_STARTS` calls

    generator.setStructureStarts(accessor, chunk, manager, world.getSeed())

and `world.getSeed()` is the WORLD seed, whichever dimension's generator
is running. So every bastion and fortress in a match nether was laid out
by `overworldSeed`, while every coordinate shipped to players came from
`netherSeed`.

The consequences were all silent and were misdiagnosed three times:

- players walked to three shipped bastion coordinates and found empty
  nether;
- the loot top-up scanned that emptiness and reported "no bastion
  chests found" while working exactly as designed;
- it was explained away first as an anchor-versus-centre offset, then
  as cubiomes being wrong, then as `ServerWorld.locateStructure` being
  wrong. A client-side "correction" was built on the second theory and
  would have overwritten correct coordinates with bad ones.

**Why it took so long: the harness never had the shape of the product.**
Every probe generated a world from a SINGLE seed via `runServer`, and a
single-seed world cannot reproduce a two-seed bug. The village and
ravine checks were sound because those are overworld and single-seed;
the nether was never once tested the way a match builds it. This is the
same lesson already recorded here about Rosetta Java 8 versus the dev
JVM - written down, then not applied where it mattered most.

`NetherStructureSeedMixin` substitutes the nether generator's own seed,
identified by its `MultiNoiseBiomeSource`. Verified in a match-shaped
world: with the world seed set to the overworld's, the bastion now
generates at the shipped coordinate with the shipped chest type.

`debug/NetherLocateHook` takes an optional fourth argument to simulate
a two-seed match world, so this class of bug is now reproducible
offline.

**The Divine Travel countermeasure works for the first time.** Until
this fix the two dimensions were correlated, and the guarantee stated
here was false.

Vanilla baselines, for reference when building the remaining RNG work:
ender pearls are ~2.13% per ingot bartered, obsidian ~8.53%, and blaze
rods a flat 50% per blaze. Each of those is a coin-flip that can decide
a match, which is why the standard pins all three.

**Measure from the link point, not the origin.** "Bastion within 14
chunks of nether spawn" quietly assumes the player arrives at 0,0,
which is true only while the portal is cast near overworld spawn.

Village, desert temple and ruined portal seeds cast at the objective, a
few hundred blocks out, so the link lands within tens of blocks of the
origin - measured across all 21 land pairs, every one passes from its
own cast point, so the assumption held for them.

Ocean seeds break it. The portal is cast at the MAGMA RAVINE, which can
be hundreds of blocks past the structure. A buried treasure seed whose
ravine sat at 283,-293 linked to roughly 35,-37, putting a bastion 192
blocks from the origin 230 blocks from where the player actually stood:
14.4 chunks, over the rule, on a seed this harness had passed. Caught
mid-run by working it out from the player's F3 coordinates, then
confirmed by re-measuring all ten ocean pairs from their ravines - one
failed, the rest moved closer, because a ravine link often lands nearer
the bastion than the origin does.

The harness now takes an optional cast point and defaults to the
origin, so land seeds measure exactly as before.

**Bastion and fortress pairing.** The intended bastion is the nearest
viable one to the origin, and it must beat its closest rival by
`BASTION_ISOLATION` - a runner who cannot tell which bastion was meant
has a guess, not a route. The fortress is then the nearest one to THAT
BASTION, searched in the bastion's own fortress region and the eight
around it.

Both halves were wrong at different times, in the same way: they
answered a question next to the one the rule asks. The fortress was
once measured from spawn, which produced a failure rate that was an
artefact of the question. Later the search was centred on the ORIGIN
and took the FIRST viable fortress in region scan order rather than the
nearest to the bastion.

**The second fix changed nothing measurable, and that is worth
recording.** An A/B over the same 40 accepted seeds produced byte
identical output, and a counter over 8,753 scanned seeds found ZERO
cases where two fortresses sit within range of the bastion at once.
Fortress regions are 27 chunks - 432 blocks - and the rule's radius is
256, so at most one fortress is normally reachable and "first" and
"nearest" cannot disagree. The fix is kept because it is correct by
construction and because raising either distance limit would make the
old code silently under-scan; the counter is kept so the exposure stays
measured rather than assumed.

The earlier note claiming two pool seeds overshot the fortress limit at
279 and 324 blocks described the origin-measured version and was stale.
No seed in the current pool exceeds it: tier-4 verification measured
every passing pair's fortress at 137 to 249 blocks from its bastion.

## Mechanics

| Guarantee | Status |
|---|---|
| Difficulty starts Easy and is never locked | built |
| Eye of ender throws standardised | built |
| Spawner RNG standardised | built |
| Sheep shearing standardised | built |
| Endermite spawn standardised | built |
| Death: respawn and continue, inventory recoverable | built (vanilla behaviour, deliberately unchanged) |
| Disconnect mid-match: rejoining resumes the same run | built — **confirmed in play**: a rejoin resumed at 12:30, not 0:00 |
| Disconnect mid-match: never rejoining | built — the opponent's next poll awards them the match after 10 min of silence |
| Disconnect mid-match: BOTH players gone | built — voided by `scripts/sweepAbandoned.ts`, no rating change, seed returned |

Difficulty toggling is a legitimate technique and must never be
restricted: Hard spreads fire faster when woodlighting a portal, and
widens piglin aggro range for a bartering pit. Runners flip to Hard and
straight back.

**Shipwreck confirmed in play 2026-09-23**, on seed#e116 -
the seed whose supply chest rolls TWO hunger points, the worst measured
anywhere including eleven of the incumbent's. Player report: "loot &
food = perfect, trees = perfect, magma ravine = perfect". That
validates three separate guarantees at once, each of which had failed a
player earlier the same day: real wood at spawn, 88 hunger points in
the supply chest, and two magma ravines within 10 chunks.

**Confirmed in play 2026-09-22, by type.** Village: smith, bastion and
fortress all walked. Ruined portal: the first filtered seed, after five
distinct defects the same day. Desert temple: three lava pools placed
and a portal cast from one of them, bastion in sight at the shipped
coordinate - the lava guarantee's failure path had been silent until
that afternoon, so this was its first run since. Buried treasure: the
magma ravine found within 3 blocks of its predicted position, though
that seed was later quarantined for the link-point rule. Shipwreck
remains untested against the current code.

**Buried treasure confirmed in play 2026-09-23**, on seed
seed#3a5b - the first pair tier 4 ever verified, and until now
never played. Treasure found at 4:49, wood at spawn fine, magma ravine
where tier 5 said it was. That was the last type with no completed
overworld route.

The wood result matters on its own: this seed predates the wood filter
entirely, so nothing had ever checked it. The player carried 57 wood.

### Nether arrival terrain

Biome is filtered; terrain is not. A seed can pass every check and
still drop the runner onto a sliver of netherrack over an open lava
sea, which happened in testing.

cubiomes cannot see terrain, so checking it means generating the nether
spawn chunks for every candidate - a cost every type would pay, not
just the ocean ones that already generate worlds. The alternative,
consistent with how lava pools and portals are handled, is to place a
small platform when the arrival point has nothing usable. Neither is
built.

**Predicted structure Y is not usable, and the code now says so.**
A structure's start is built before terrain exists, so its X and Z are
exact while its Y is where the structure would sit on flat ground.
Real generation then moves it. Measured on this project:

| structure | predicted | actual |
|---|---|---|
| desert temple | y[64..78] | chests at y53 |
| shipwreck | y[90..98] | chest at y39 |

Three separate features trusted it and quietly did nothing: a
mob-suppression box hovering above its temple, a chest scan looking in
empty air, and a loot verifier returning confident false negatives.
Each reported success.

The field is now named `predictedBox`, so using it is a decision, and
`xzBox()` returns the footprint with Y replaced by the full column.
Everything that only needs the footprint uses `xzBox()`; `ContainerScan`
ignores Y and reads chunk block-entity maps, so that costs nothing.
Anything needing a real vertical bound derives it from what it FINDS -
the way the temple quiet zone is now built from its chests.

**Abandonment resolves itself.** A match left pending forever used to
need a human to clean it up, and it held a seed out of the pool while
it sat there.

The live poll is the heartbeat — a client asking for match state is
demonstrably still playing — so no scheduler is needed for the common
case. If one player has been silent for **10 minutes** while the other
is polling, the one still there is awarded the match. Splits count as
proof of life too, or a client that reports progress without polling
would look abandoned and hand away a match it was winning.

Abandoning has to cost the match. If it did not, quitting would be a
way to deny an opponent a win they were about to earn — a stalemate on
demand. Forfeiting is the honest version and is one click away.

Ten minutes is deliberately generous: a player who crashes and
relaunches is back well inside it, and rejoining resumes the same run
rather than starting a new one. The cost of waiting is that an
abandoned match lingers; the cost of being hasty is handing someone a
loss while their game is still loading.

When **both** players vanish nobody polls, so nothing fires. Those are
swept out of band and **voided** — neither player was there, so there
is nobody to award a win to and nobody who deserves a loss. The seed
goes back to the pool, since nothing was wrong with it.

## Match format

| Guarantee | Status |
|---|---|
| Both players race the same seed in separate worlds | built |
| Seed type announced with a 10-second countdown before the run | built — confirmed in play |

**The countdown is a real mechanic, not a nicety.** the incumbent tells
both players which seed TYPE they have drawn - village, desert temple,
shipwreck, buried treasure, ruined portal - and counts down ten
seconds before the run begins. The layout stays hidden; what is shared
is the opening you will be running, so the route is planned rather
than improvised from a cold start.

The screen is a PAUSE screen, and that is the mechanism rather than a
side effect: MatchClock already refuses to claim a run start while the
game is paused, so closing the screen at zero is what releases the
clock. No extra coordination, and the rule that loading is never
charged to the run still holds.

The countdown starts on the player's FIRST PLAYABLE TICK, not when the
match is made. Starting it at matchmaking was wrong and invisible -
world generation takes about twelve seconds, so a five-second countdown
begun then had already expired before the player could see it and the
screen never appeared at all. Confirmed in play: reveal at 11:19:55,
clock at 11:20:06.

**ZSG is not the standard to copy.** It filters for fast single-player
times (sub-5 theoretical), with much tighter distances — bastion within
96 blocks against the incumbent's 14 chunks. Same mechanics, different purpose:
ZSG optimises a time attack, the incumbent balances a race. Its `terrain_checker`
idea is worth borrowing; its distances are not.

**The countdown is shown once per run, not once per join.** A player who
crashes out and rejoins is dropped straight back into the world with
their clock still running. The ten seconds are planning time *before* a
race; someone twelve minutes into one has already planned, and showing
the screen again would take ten seconds off a run in progress.

The client cannot work this out for itself. The countdown is shown on
the first playable tick, and whether the run start is a fresh claim or a
resume is only known when the claim is made - which happens deliberately
*after* the countdown, so that nobody's loading is charged to their run.
So the server says it: both the queue-join response and the live match
poll carry `yourRunStartedAt`, and either one being present means skip.
The queue-join answer arrives before the world is even created, so it
normally beats the player to their first tick; the poll is the backstop,
and closes the screen if it has already opened.

Quitting *during* the countdown is not a rejoin by this rule - no run
start was minted, so that player still gets their planning time.

Confirmed in play 2026-09-22. A player 20:46 into a run quit to title
and re-queued: the match response arrived at 14:17:23, the skip was
decided at 14:17:25 and the clock resumed at 20:46 with no reveal
screen. Two seconds is the queue-join path, not the three-second poll,
so the primary channel is the one that fired - the backstop was not
needed. Fresh matches in the same session still showed the countdown
normally, so the skip is scoped to rejoins rather than disabling the
screen.

## Disputes

**Bad seed: both players, or nothing.** Either player can vote that a
match's seed is unplayable. Nothing happens until the opponent agrees.
When they do, the match is voided — no winner, and **neither rating
moves** — and the seed is pulled from the pool permanently.

| Guarantee | Status |
|---|---|
| Either player may raise a bad-seed vote | built |
| A vote does nothing until the opponent agrees | built |
| A voided match changes no ratings for either player | built |
| The seed is quarantined, never dealt again | built |
| Quarantined seeds are kept, with who voided and why, for review | built |
| Never fired in a real match | — |

The single-player version of this is an obvious exploit: someone losing
a perfectly good run calls it bad and escapes without the rating loss.
Requiring both is what makes it safe. Two opponents actively racing
each other have no shared interest in lying, which makes their
agreement a stronger signal than any check the pool build could run —
and it needs no moderator, which is the entire point of this project.
The rule is mechanical and its outcome is visible to both sides.

**No timeout, deliberately.** If the opponent never agrees, the match
simply carries on. A proposal that expired into a void would hand back
the same exploit: raise it, disconnect, escape.

Seeds pulled this way are flagged in place rather than deleted, and
reviewed by the maintainers — never surfaced to players — via
`scripts/reviewBadSeeds.ts`. Every row is a filter bug with evidence
attached, and a cluster in one seed type points straight at that type's
filter stage. A pool that silently shrinks teaches nothing.

## Pool

**31 live seeds**, rebuilt 2026-09-22, with 11 quarantined:

| type | live | quarantined |
|---|---|---|
| village | 9 | 1 |
| shipwreck | 8 | 2 |
| desert temple | 6 | 3 |
| ruined portal | 6 | 3 |
| buried treasure | 2 | 2 |

Buried treasure is short because far fewer candidates pass the
two-magma-ravine check than shipwreck does.

**Tiers 4 and 5 rejected 7 of 23 freshly filtered pairs - 30%.** Six
failed in the nether, three of those shipping a bastion coordinate 487
to 577 blocks from any real bastion. Every one had passed the cubiomes
filter. That rate is the argument for the held-load flow below: the
filter's output is not a pool, it is a list of candidates.

For scale: the incumbent draws from roughly a million pre-vetted
worlds. Ours is a test pool, not a production one — at this size seeds
repeat quickly under real traffic, and did: one seed came up eight
times in a row before the draw was made random.

**The pool is rebuildable from this repo, but not reproducible.**
`seedtypes` picks a random start seed on each run, so a rebuild
produces a different, equally valid set - not the same seeds. The
candidate JSON in `seed-filter/output/` is gitignored, so the exact
seeds currently live exist only in DynamoDB. The tier-4 verification
results ARE committed, in `seed-filter/results/`. Commit that directory
deliberately if a specific pool ever needs to be reproducible.

**Load held, verify, then release.** Tiers 1 to 3 verify a SEED; tiers
4 and 5 verify a PAIR, and a pair does not exist until the loader has
made one. So `loadSeedPool.ts --held` writes every row `used=true`, and
`seed-filter/verify-and-release.sh` runs both tiers and releases only
what passes. Nothing unverified is ever drawable, not even briefly -
and briefly is all it takes, because a seed is dealt the moment someone
queues.

Built by `seed-filter/build-pool.sh` in tiers of increasing cost:

1. **cubiomes** — structures, distances, biomes, bastion type, wood
   near spawn. Microseconds per seed.
2. **jigsaw** — a cheap blacksmith PRE-filter. Needs Minecraft's
   generator but no chunks, ~66 ms per seed. It tests a piece *name*,
   which over-reports by roughly 3x: in the last build 40 raw
   candidates gave 16 jigsaw passes and 5 real smith chests. It is a
   pre-filter and never the thing that decides the pool — stage 4
   refuses to load if the verification output is missing.
3. **generated world** — magma ravines for the ocean types, and real
   blacksmith chests plus the iron/diamond threshold for villages.
   Carvers and loot tables are both invisible to cubiomes, so the
   world must be built and inspected. Roughly 12 s per seed, and the
   reason a pool build takes half an hour.

4. **match world** — the nether, generated the way a MATCH generates
   it: world seed = overworld seed, `MatchState.netherSeed` set, the
   structure-seed mixins live. `seed-filter/verify-pairs.sh`. Roughly
   40 s per pair.

Tier 4 exists because tiers 1 to 3 all verify a SEED, and a match does
not ship a seed - it ships a pair, a world built from one seed whose
nether is redirected to another, plus a coordinate telling the player
where to go. Every earlier harness generated single-seed worlds, which
cannot reproduce a two-seed bug by construction. The first run over the
live pool found 4 of 19 pairs out of spec, and three of those were
shipping a bastion coordinate with nothing at it - 452, 522 and 828
blocks from the nearest real bastion, zero containers at two of them.
That is precisely the failure players had been reporting as "the
bastion wasn't at the coords you gave me", and no tier below 4 could
see it.

The FORTRESS leg was confirmed in play on 2026-09-22, on pair
7be07d50: fortress at 176,64, 229 blocks from the bastion, reached in
under two minutes from the portal (nether 5:14, fortress 7:09). This
mattered because no fortress coordinate is shipped to the player -
tier 4 measures the distance and the runner finds it the real way - so
"findable at that range" had been assumption, not evidence.

Tier 4 was confirmed by hand on 2026-09-22: a player walked to the
shipped bastion coordinate of pair 39c3b474 in a real match and found
it, then bartered there. The harness and the product finally agree
about the same world.

**Measure generation, not the locator.** Tier 4's first version used
`locateStructure` for ground truth and failed two good seeds. The
locator walks outward through the structure region grid and returns the
first viable placement it meets - *a* bastion in an early ring, not the
*nearest* bastion. On one pair it reported the bastion 265 blocks out,
a fail, while a real hoglin stable with nine chests stood 160 blocks
out at the shipped coordinate. It now enumerates `STRUCTURE_STARTS`
chunk by chunk, which is the pass that actually decides placement.
Filter on `hasChildren()`: the map carries placeholder entries for
features considered and not placed, and counting those would turn "no
bastion here" into a confident wrong coordinate.

5. **overworld opening** — runs the REAL `MatchWorldSetup` in a
   generated match world and then asks whether the opening it was
   supposed to create is actually there.
   `seed-filter/verify-routes.sh`. Roughly 40 s per pair.

Tier 5 exists because tier 4 verified the nether and nothing verified
the overworld. The filter checks that a structure is PREDICTED near
spawn and then trusts the runtime to supply everything a route needs -
lava to cast a portal from, a portal that can be lit, chests with a
floor under them. Nobody ever checked that the supplying WORKED, and
twice it did not: `RuinedPortalPlacer.place` and `LavaPoolPlacer.place`
both report failure through a return value that was discarded at the
call site. The first shipped a player into open ocean with no portal
and no lava.

What tier 5 asserts, per type: `setupFailure` is null; a ruined portal
seed has a usable portal, vanilla's or placed; village and desert
temple have BUCKETABLE lava near the objective and containers at it;
shipwreck and buried treasure have a usable magma ravine.

**Count what a runner can reach.** The first version of the lava check
counted any lava source between y4 and y80 and returned 1269 for a
village - deep underground lava is nearly everywhere, and a check that
passes everything is not a check. It now counts source blocks with air
directly above, within 24 blocks of the surface: lava a player can
actually put in a bucket. The same seeds then scored 17 to 64.

Every tier past the first exists because a requirement was silently
unchecked and shipped. Villages went out without blacksmiths until one
turned up in a live match — twice, because the first fix verified a
piece name rather than a chest.

**Parallel verification works. It was a port collision.**

For a long time this said parallel runs fail most of the time and the
cause was unknown, with Gradle cache contention as the suspect. That
was wrong, and it was wrong in the way folklore usually is: the
observation was real, the explanation was never tested, and the belief
outlived several chances to check it.

Every worker launched a server without setting `server-port`, so they
all tried to bind the default and all but one died. The fix is one
line per harness — a distinct port per worker — and the checks that
had been believed unparallelisable now run clean across every worker.

The ERROR marker stays regardless. A crashed worker once got counted
as a genuine result, turning crashes into a confident, wrong "35 of 40
villages have no blacksmith", and a failure must never be readable as
a zero no matter what caused it.

**The wood check tests a BIOME, not a tree.** `woodNearby` in
`seedtypes.c` samples biome ids every 16 blocks within 5 chunks of
spawn and passes if any one of them is wooded. A biome is not a tree.

Found in play 2026-09-22 on shipwreck seed#bf7f: spawn at
-256,63,-23 in open ocean, 47,000 water blocks at spawn height, a
jungle biome clipping the sample radius - and the only logs within 80
blocks were the SHIPWRECK'S OWN HULL, 61 blocks out and 12 blocks under
water. The player had no wood, so no crafting table, so no run.

This is the same mistake as every other tier in this file: a piece name
instead of a chest, an obsidian count instead of a frame, "a portal
exists" instead of "it can be lit". The cheap check is a pre-filter and
must never be the thing that decides the pool.

The fix is a generated-world stage that counts actual `*_log` blocks
above sea level within reach of spawn, EXCLUDING any inside a structure
bounding box - a shipwreck's hull is wood the filter must not count.
Ocean types need it most, because their spawns are the ones where a
wooded biome can be technically nearby and practically unreachable.

**Bastion burial was investigated and is NOT a problem.** A player got
lost in a bastion on 2026-09-23, found one chest, could not dig out to
orient themselves, and reported it as "partially buried". Three
measurements say otherwise:

| | that bastion | six the incumbent bastions |
|---|---|---|
| roof exposure | 64.9% | 7.1 - 29.8% |
| solid blocks above its chests | 0 | 0 (median, all six) |
| structure size | 1,859 top blocks | 1,483 - 2,366 |

It is more exposed than any bastion the incumbent ships, its chests
have open air directly above them, and it is normally sized. The chest
count differs - 3 against their 4 to 12 - but chest count varies by
bastion type and that one was a bridge.

So no filter was added. Writing one would have rejected seeds for a
property the incumbent does not filter on either, which is the same mistake as
the zero-burial shipwreck rule that would have thrown out one of their
own seeds. What the player hit was a sprawling variant and the ordinary
difficulty of navigating it.

Recorded because the alternative is re-investigating it: the first two
metrics tried here - fill fraction and roof exposure - both measured
something other than the reported experience.

## To do

Engineering debt that is not itself a match guarantee, kept here so it
lives in one place rather than in somebody's memory.

**Parallel seed verification — RESOLVED.** This entry used to record
that parallel runs failed most of the time for reasons nobody had
established, with shared Gradle cache contention as the suspect and
two untried fixes listed.

The cause was a port collision. Every worker started a server without
`server-port`, so they contended for the default and all but one died
on startup. Each harness now assigns a distinct port per worker, and
the stages previously believed serial-only run in parallel with no
errors.

Kept here rather than deleted, because the interesting part is not the
bug. It is that a wrong explanation was written down once and then
believed for weeks, shaping how the pipeline was built around it. The
observation was real and the diagnosis was never tested.

The ERROR marker that guards the consequence stays: a crashed worker
must never be readable as a genuine zero, whatever the cause.

**The client explains every void as a bad-seed vote.** `LiveMatchPoller`
prints "both players agreed the seed was unplayable" whenever it sees
`status: voided`, but the match row carries a `voidReason` and there
is more than one. A match voided by the abandonment sweep reads
`abandoned by both players` server-side and would still tell the
player their seed was voted unplayable. Same class as the forfeit log
below: the client asserting something the server did not say.

**The client reports a forfeit it did not achieve.** `Forfeit` logs
`Forfeited match <id>` when its request is sent, not when the backend
accepts it. A forfeit racing a bad-seed void has already been refused
server-side while the client logged success. Harmless today — the
outcome was better for the player either way — but it means the client
log is not evidence of what happened.

**The pool's villages predate the resource rule.** Its 29 villages
were selected on "has a real smith chest" alone, before the iron and
diamond thresholds were wired into verification. Every one is verified
playable; none has been checked against either threshold. Re-verifying
is about seven minutes.

**Seven guarantees have never run in a live match.** Flint mirroring,
hoglin drops, drowned tridents, suspicious stew, wither skeleton
crowding, the nether arrival pad, and shipwreck chest normalisation —
the last verified in a generated world but not in a match. Today's
record is that this category is where the real bugs are: a barter
mixin that compiled and passed every harness check crashed the server
thread at the first piglin.
