# What this changes about Minecraft

A match here is not pure vanilla, and pretending otherwise would be the
one thing this project exists not to do. Everything below is a
deliberate change. If you find behaviour that differs from vanilla and
is **not** on this list, that is a bug — please report it.

Two principles decide what belongs here:

**Variance that both players face is fine. Variance that hits one of
them is not.** Two runners racing the same seed should be separated by
their decisions, not by which of them the dice favoured. A coin flip
either player could lose is not fair just because it is impartial.

**Difficulty stays.** Nothing here makes the game easier — bastions are
still hard, the dragon still kills people. The changes remove
*asymmetry*, not challenge.

Every item is in the source, and the mod jar can be unzipped and read.
Where a change removes a vanilla roll, the vanilla behaviour is quoted
from the decompiled source so you can check the claim rather than take
our word.

---

## 1. Rolls mirrored between the two players

These still vary — you cannot predict them — but both players in a
match get the **same sequence**. One runner cannot be handed seven
blaze rods while the other gets three.

| Change | Vanilla |
|---|---|
| **Blaze rods** follow a fixed per-match sequence | flat 50% per kill, no floor |
| **Piglin barters** have fixed contents per trade index | rolled independently per barter |
| **Hoglin drops** follow a fixed per-match sequence | varies per kill |
| **Flint from gravel** follows a fixed per-match sequence | flat 10% per block, no floor |

Flint is the clearest case: a runner needs exactly one piece for flint
and steel, so vanilla's 10% is a coin flip with a long tail — one
player takes flint off the first block, the other digs twenty.

## 2. Rolls fixed outright

| Change | Vanilla |
|---|---|
| **Shearing a sheep always gives 3 wool** | `1 + random.nextInt(3)` — 1 to 3 |
| **A thrown eye of ender always survives** | `random.nextInt(5) > 0` — 20% chance to break |
| **Mob spawner timing and position are seed-deterministic** | drawn from the world's shared `Random`, so identical seeds diverge |
| **Dead bush and iron golem drops are standardised** | ranged rolls |

Wool matters because beds are the 1.16 dragon strategy: a 1-to-3x swing
in wool per sheep is a swing in how many sheep you must find.

The spawner change is subtler. `MobSpawnerLogic.update()` draws from
`world.random`, a **single `Random` shared by every random-consuming
system in the game** — weather, mob AI, block ticks. Two players on the
same seed diverge the moment anything else touches it.

## 3. Hazards removed

Each of these is a hostile outcome with no skill component, capable of
ending a run that was otherwise identical to the opponent's.

| Change | Vanilla |
|---|---|
| **Endermites never spawn from pearls** | 5% per landing |
| **Drowned do not hold tridents** | 9 damage at range, through water, often from an unseen mob |
| **Suspicious stew has harmful effects stripped** | wither or poison depending on an invisible flower |
| **Wither skeletons cannot crowd past a cap** | vanilla will stack an impassable horde into a corridor |
| **Hostile mobs and bats are kept out of the opening structure** | ordinary spawning |

## 4. The match world is partly built, not only found

This is the largest deviation and the one most worth understanding.

A filtered seed guarantees only that a **structure** sits near spawn.
Everything else a route depends on — lava to cast a portal from, a
floor under chest loot, a ruined portal frame that can actually be lit,
enough food in a shipwreck's supply chest — is **placed by the mod**
when the world loads.

Searching for seeds that satisfy all of it at once is not affordable.
So the seed supplies the structure and the mod supplies the rest. Both
players get identical worlds; neither gets a vanilla one.

The two halves of the world also use **different seeds**: the overworld
and the nether are paired from separate searches. This deliberately
breaks the correlation vanilla has between them, because that
correlation is what makes inferring one from the other possible.

**What the seed is required to supply.** Distances are measured from the
actual world spawn, not the origin. Every seed is one of five opening
types and carries that type's structure within 3 chunks (ruined portal),
4 (shipwreck), 5 (desert temple, buried treasure) or 7 (village), plus
real wood near spawn and — on village and temple seeds — a river within
6 chunks. In the nether: a bastion within 14 chunks of nether spawn and
at least 10 chunks clearer than the next nearest, and a fortress within
16 chunks **of that bastion** rather than of spawn. Arrival is never in
Basalt Deltas.

**A ruined portal has to be completable.** Vanilla's are frequently not
— flat debris, or buried. A qualifying one is a vertical frame at least
4 by 5 of real obsidian, missing at most 2 non-corner blocks, with no
crying obsidian in a slot that must be filled, at least partly above
ground, and with a chest. If the seed's own portal fails that check at
world creation, the mod places one.

**What the mod places.** Lava pools about two chunks out on village and
temple seeds; lava and water at a ruined portal, so both the obsidian
and the bucket route are open; a shipwreck's missing chests, when
vanilla generated a half-wreck; a small landing pad, only when the
nether arrival has no standable ground.

**Chest loot is topped up to a floor.** The loot table rolls normally
and only the shortfall is added, so a good roll stays a good roll:

| Where | Floor |
|---|---|
| Village smith | 3 iron (+4 from the golem), or 4 iron and 3 diamonds |
| Desert temple | 7 iron, 52 hunger points of food |
| Ruined portal | 27 nuggets (three ingots — a bucket), 2–4 obsidian |
| Shipwreck, buried treasure | 7 iron equivalent, 88 hunger points |
| Bastion, across its chests | 3 iron, 5 obsidian, 48–64 string |

Iron is counted in the unit the route uses — ingots at a village,
nuggets at a portal, both together on the ocean routes — and food in
hunger points rather than items, because thirteen rotten flesh is not
thirteen meals. Top-ups are deterministic: same container, same slot,
both players. A temple's string and sand are route material and are
**not** yet guaranteed.

[SPEC.md](SPEC.md) tracks each of these against what the code actually
does today, including the ones marked not built.

## 5. Client-side only

No effect on gameplay or fairness.

| Change | Why |
|---|---|
| Brightness slider unlocked past vanilla's cap | every serious runner already does this; leaving it locked advantages people who know how |
| Title screen, lobby and pause menu additions | the ladder UI |
| Loading screen shows the logo as chunks finish | cosmetic |

## 6. Not verified in play yet

Honesty about our own coverage. These are implemented and unit-correct
but have **not been observed firing in a real match**:

- **Hoglin drops** — needs a player-credited melee kill
- **Suspicious stew** — needs a runner to actually find and eat one
- **Drowned tridents** and **wither skeleton crowding** — need runs that
  reach those situations

If you hit one of these, we want to hear what happened either way.

---

## What is not changed

- Terrain generation, structure generation and biome placement are
  vanilla for the seed, except where section 4 says otherwise
- Combat, movement, hunger and damage are untouched
- **Death is vanilla, and deliberately so.** Dying on purpose is a
  real technique — it refills hunger and returns you to your spawn
  point — so a ladder that penalised death, ended the match on it, or
  touched the run clock would be banning a legitimate line. Nothing
  here reacts to a death at all. It is recorded in the replay event
  feed and that is the whole of it.
- No advantage is given to either player over the other; every change
  applies to both sides of a match identically
- Nothing reads or transmits anything about your machine beyond your
  Minecraft identity and your match results
