<img src="brand/logo_alt_preview.png" alt="alt" width="200">

A 1v1 ladder for Minecraft 1.16.1 speedrunning (RSG).

Two players race the same filtered seed in their own worlds. Whoever
kills the dragon first wins. Ratings move, seasons reset.

**Status: pre-alpha.** It works end to end, but it has had very little
real play. Expect rough edges.

---

## Get started

1. **Make sure you are invited.** Pre-alpha testing is **invite-only**:
   logging in checks your Minecraft account against an allowlist.
   [Open an issue](../../issues/new) with the Minecraft username you
   want to play on, and ask for an invite.

   Do this first. The client installs fine without one and nothing
   warns you — it is **matchmaking** that refuses you, so an uninvited
   account gets all the way to Find Match before finding out.
2. **[Download the latest release](../../releases/latest)** — take
   `alt-<version>.mrpack` for Prism, MultiMC, Modrinth App or ATLauncher;
   take the two `.jar` files if you use the official Minecraft
   launcher.
3. **[Follow INSTALL.md](INSTALL.md)** for the exact steps, including
   which Fabric and Fabric API versions are required and why they are
   exact.
4. **[Read DEVIATIONS.md](DEVIATIONS.md)** before you play. A match is
   not pure vanilla and every difference is published. Anything that
   differs and is not on that list is a bug worth reporting.

The rest of this file is what the project is and how it works.

---

## Playing

1. Click **alt  speed-running** at the top of the title screen.
2. It verifies your account automatically — you should see your
   username, rating and season points. No password, no separate signup;
   it confirms ownership through Mojang using the session you are
   already logged in with.
3. Click **Find Match**. You are paired with someone near your rating.
   The range widens the longer you wait.
4. A world is generated and you are dropped straight into it. The timer
   starts.
5. Race. The HUD top-left shows your splits against your opponent's —
   green means you are ahead, red means behind.
6. First to kill the dragon wins. Ratings update automatically.

**Leaderboard** on the alt menu shows where everyone stands — rating,
season points and a win-loss-forfeit record. Everyone starts at 1500.
PaceBot races but is not ranked, since a bot holding a place on a ladder
it is not competing on would be meaningless.

**Press F6 to forfeit** if you want out of a bad seed. It asks for
confirmation first. Your opponent wins and your rating moves as it would
for any loss, but the match is recorded and shown as a **forfeit**, not
a loss.

---

## Who can play

Anyone with a legitimate Minecraft account.

We don't consider bans from other platforms, past conduct, or
controversy. This ladder judges runs. If you can log in, you can queue.

The only thing that removes you is **cheating here** — and if that
happens you get the evidence, in plain English, with a human decision
behind it. Integrity checks flag runs for review; they never ban
automatically, and every flag records a reason you can argue with.

This is a deliberate choice rather than an oversight. A competitive
ladder that adjudicates who deserves to compete ends up adjudicating
everything, and we would rather adjudicate one thing well.

---

## How to behave

**Don't say anything you wouldn't say to their face.**

That's the whole rule. It is the arcade standard, and arcades were not
polite places — trash talk has always been part of competition. What
was never part of it is the stuff people only do because there is
nobody standing in front of them.

So this is not a rule against being aggressive. It is a rule against
being anonymous about it.

It is a standard, not a word list. Word lists get lawyered; this one
asks a question you already know the answer to.

Three things are built this way rather than moderated:

- **Your name is real.** Accounts are verified through Mojang, so there
  are no throwaways.
- **There are no DMs and no friends list.** The only contact is inside a
  match you are both in. Nobody can follow you home from a loss.
- **Anything said in a match stays attached to that match**, and is not
  ephemeral. A report is "look at the match", not one word against
  another.

Enforcement works like the cheating checks: a human looks, you get the
reason in plain English, and you can argue with it. Behaviour that would
get you thrown out of an arcade will get you thrown out of here — the
standard only means anything if somebody stands behind it.

---

## What is different from vanilla

A match is not pure vanilla and never claims to be. Two kinds of thing
change: some randomness is **mirrored or fixed**, so neither player is
handed a better run by luck, and the world is **partly built** rather
than only found.

This is the short version. [DEVIATIONS.md](DEVIATIONS.md) is the
complete list, with the vanilla behaviour quoted beside each change.
[SPEC.md](SPEC.md) adds the part that is promised but **not built yet**.

### The seed is filtered

Vanilla will happily spawn you in open ocean with no route at all. The
pool only admits seeds with a real opening, and every seed is one of
five types — village, desert temple, ruined portal, shipwreck, buried
treasure. You are told the type before the world loads. You are not
told the layout.

The structure is close, measured from your **actual world spawn** and
not from the origin — Minecraft's spawn search routinely lands a few
hundred blocks out, and measuring from the origin passes seeds whose
"nearby" village is a long walk:

| Type | Structure within |
|---|---|
| Ruined portal | 3 chunks |
| Shipwreck | 4 chunks |
| Desert temple, buried treasure | 5 chunks |
| Village | 7 chunks |

Also filtered: real wood near spawn — counted as actual logs in the
generated world, not a wooded biome clipping the search radius and not
a shipwreck's own submerged hull; a river within 6 chunks on village
and temple seeds, for boat routing; and a blacksmith whose chest
genuinely generated. Villages qualify on what they contain rather than
which biome they sit in, so all five variants are eligible.

The five types are dealt in even proportion, and you will never be
given a seed you have played before.

**The overworld and the nether are seeded independently.** You cannot
infer nether structure positions from overworld terrain — Divine Travel
does not work here, deliberately.

### The nether is guaranteed too

- A **bastion within 14 chunks of nether spawn**, and unambiguous: at
  least 10 chunks clearer than the next nearest one, so which bastion
  was meant is never what decides a match
- A **fortress within 16 chunks of that bastion** — not of spawn. The
  leg that matters is the second one, because the route is spawn →
  bastion → fortress
- All four bastion types are eligible. The type is recorded, not
  filtered out
- Your arrival is never in Basalt Deltas, and if it would put you on a
  sliver of netherrack over open lava, a small pad is placed

Not built: nothing checks that the terrain **between** spawn, bastion
and fortress is walkable. You can be walled off and have to route
around it.

### Ruined portals are completable

Vanilla ruined portals are often unrunnable — twelve obsidian lying
flat on the ground, or a frame buried under terrain. Ruined portal
seeds are **filtered, not built**: the portal is the seed's own vanilla
one, and to qualify it must be

- a vertical frame at least 4 wide by 5 tall, of real obsidian,
- missing at most 2 non-corner blocks (a portal lights without its
  corners),
- with no crying obsidian in a slot you would have to fill — clearing
  that needs a diamond pickaxe, which is not on the route,
- at least partly above ground and not submerged,
- and it must have a chest, because the missing obsidian, the light
  source and the iron are all guaranteed *into* that chest.

If the seed's own portal fails verification at world creation, one is
placed instead. Lava and water for the bucket route are placed either
way, so both routes are always open.

Known gap: **shipwrecks get no equivalent check.** One was dealt
entombed under terrain, with all three chests present and unreachable.
A player's bad-seed vote found that, not our filters.

### Chest loot has a floor

Vanilla loot tables spread wide enough that the same seed type can be a
comfortable opening or a dead run. So the roll happens normally and
only the **shortfall is topped up**. A seed that rolls well keeps its
good roll, so there is still a spread above the floor and a reason to
read what you actually found.

| Where | Guaranteed |
|---|---|
| Village smith | 3 iron, plus the golem's 4 — or 4 iron and 3 diamonds |
| Desert temple | 7 iron, 52 hunger points of food |
| Ruined portal | 27 nuggets — three ingots, exactly a bucket — and 2–4 obsidian, varying by seed |
| Shipwreck, buried treasure | 7 iron equivalent, 88 hunger points of food |
| Bastion, across all its chests | 3 iron, 5 obsidian, 48–64 string — four to five beds |

Iron is counted in the unit the route cares about: ingots at a village,
nuggets at a ruined portal, both together on the ocean routes. Food is
counted in **hunger points, not items**, because thirteen rotten flesh
is not thirteen meals. Iron golems always drop 4.

Top-ups are deterministic — same container, same slot, both players.

Not guaranteed yet: a temple's **string and sand**. Both are route
material rather than junk — string is wool is beds, and sand is how you
get out of the pit the chests sit in.

### Placed, not found

Searching for a seed that satisfies everything a route needs at once is
not affordable, so the seed supplies the structure and the mod supplies
the rest. Both players get identical worlds; neither gets a vanilla one.

- **Lava pools** about two chunks out on village and temple seeds —
  three of them, to cast a portal from
- **A portal frame**, when the seed's own one does not pass
- **A shipwreck's three chests**, when vanilla generated a half-wreck
  holding one
- **A nether arrival pad**, only when the arrival is otherwise
  unstandable
- **No hostile mobs or bats inside the opening structure**, so they do
  not pollute a pie-ray reading

Ocean seeds are checked rather than built: two magma ravines within 10
chunks, with the bubble columns that make them findable in the first
place.

### Rolls both players share

These still vary — you cannot predict them — but both players get the
same sequence, so one runner cannot take seven blaze rods while the
other takes three.

- **Blaze rods**, on a fixed per-match sequence with a pity floor
- **Piglin barters**, fixed per trade index, with obsidian and pearls
  guaranteed over a window of trades
- **Hoglin drops**, on a fixed per-match sequence
- **Flint from gravel**, two per twenty breaks — vanilla's rate without
  vanilla's tail, so the first piece arrives inside ten

These only apply to kills the game credits to a player. A hoglin that
burns to death unseen falls through to vanilla rolls, which is a real
hole: cooking hoglins is legitimate, and a cook gets vanilla variance
while a melee kill gets the schedule.

### Rolls fixed outright

- Shearing a sheep always gives 3 wool
- Thrown eyes of ender never break
- Dead bushes always drop 2 sticks
- Spawner timing and position are seed-deterministic, rather than drawn
  from the single `Random` the whole world shares — two players on one
  seed diverge the moment anything else touches it

### Hazards removed

Each of these can end a run that was otherwise identical to the
opponent's, with no skill component on either side.

- Ender pearls never spawn endermites
- Drowned never hold tridents
- Suspicious stew has its harmful effects stripped
- Wither skeletons cannot crowd past a cap, so they cannot wall a
  corridor

### The pack, and your other mods

The download is one file. `alt-<version>.mrpack` carries the `alt` mod
and Fabric API embedded rather than fetched, and your launcher installs
Minecraft and the Fabric loader itself — so nobody picks a loader
version and nobody can pick the wrong one. On the official Minecraft
launcher, which cannot import a pack, you place the same two jars by
hand.

**The versions are exact, not minimums**: 1.16.1, one Fabric loader
build, one Fabric API build. [INSTALL.md](INSTALL.md) says why each one
is pinned.

Nothing else is bundled.

**Any mod not listed below is not legal in a match.** That is a
whitelist, and it is deliberate: a banned list is a list of the cheats
somebody already thought of, and it leaves a runner unable to tell
whether the thing they just installed is on it. The principle behind the
list is the thing to argue with — a mod is legal if it changes how the
game is *drawn* or how fast it runs, and not legal if it changes game
state, reveals information the seed did not give you, or automates
input.

| Legal alongside `alt` | What it does |
|---|---|
| Sodium, Lithium, Starlight | performance and rendering; no game state, no world information |
| SpeedRunIGT | timing and splits |
| MCSR Fairplay | validates your resource pack against the rules, so "my pack is legal" is demonstrable rather than asserted |

**Fairplay is the incumbent's tool, and named here anyway.** It checks
your own packs on your own machine and reports to nobody, and a validator
written by the people who wrote the pack rules is the reference
implementation of those rules — reimplementing it to avoid the
awkwardness would be pride, not integrity. The cost is real and worth
stating: if their tool moves, this rule moves with it, and that is an
argument for eventually shipping our own check rather than a reason to
leave players with nothing today.

Explicitly not legal: macros, autoclickers, input-altering scripts, and
anything that surfaces world information — seed readers, structure or
ore locators, entity outlines through terrain, a map of a chunk you have
not seen. Those are the run, not the graphics.

**To get something added, [open an issue](../../issues/new).** You get an
answer in public with a reason, and the list either changes or it does
not. A list nobody can petition is just an allowlist with better
manners, which is the thing this project exists to object to.

**What is loaded is recorded and shown to both players.** Your client
reports its mod list when you log in, it goes into the match record, and
the match detail screen prints what each of you had beyond the pack. That
is deliberately symmetric: a record only one side can see settles nothing
between two people arguing about a match, and being able to show that you
were running nothing clears you as readily as it implicates anybody.

**Nothing is gated on it yet, and it is not an anti-cheat.** Anyone able
to patch the client can patch what it reports. Deliberate cheating is
caught by the position timeline against the split record, with a human
looking — the mod list is evidence in that conversation, not a lock on
the door.

The check that refuses a queue join is written and **switched off**. The
list has never been compared against what real clients actually carry,
and turning it on before that would turn honest players away over a list
nobody has checked. When it does go on, you will be refused at **Find
Match**, with the mod named — never mid-match, and never after a run you
have already finished. A run that happened, happened.

**None of the listed mods has been run alongside `alt` yet.**
SpeedRunIGT is the one to watch: `alt` keeps its own match clock and
reports splits to the backend, and two timers disagreeing in front of a
disputed match is worth finding now rather than then. If you run one of
these and something breaks, that is a bug report we want.

Resource packs are yours, with one limit: **fullbright packs, anything
pushing brightness past 5.0, and anything altering shadow rendering are
not permitted** — nor is anything that makes blocks see-through or
otherwise shows you what vanilla hides. The brightness slider is
unlocked in the mod instead, which is the sanctioned way to get the same
thing — see [Recommended settings](#recommended-settings).

---

## What gets recorded

Worth knowing up front, because this is a competitive ladder:

- Your Minecraft username and UUID
- Your split times and match results
- **Which mods you have loaded**, collected once at login — Fabric
  cannot load a mod after that without a relaunch — and shown to your
  opponent on the match detail screen, as they are shown to you
- A **position timeline** — where you were, roughly once a second,
  during a match. This is used to check that reported splits match
  where you actually were. It is not video, it is about 4KB per run,
  and it is stored privately.

Integrity checks flag runs for human review. They never ban
automatically, and every flag records a plain-English reason so it can
be argued with.

---

## Known rough edges

- Quitting to the title screen without forfeiting leaves the match
  pending until your opponent's client claims it or the abandonment
  sweep voids it
- If nobody else is queuing you will wait indefinitely, with only a
  timer for feedback
- The seed pool is finite; matches fail with an error once it runs out

Dying is not a rough edge: it is vanilla on purpose. Deliberate death
resets hunger and returns you to spawn, which is a real technique, so
nothing here penalises it or ends the match on it.

---

## Repo layout

| Path | What |
|---|---|
| `mod/` | Fabric mod (Java 8 target, Yarn mappings) |
| `backend/` | AWS CDK: Lambda, DynamoDB, S3 |
| `seed-filter/` | cubiomes seed filtering and structure queries |
| `brand/` | logo generator and assets |
| `tools/cubiomes` | vendored submodule |

Clone with `--recurse-submodules`, or run
`git submodule update --init` afterwards.


## Recommended settings

**Brightness: drag the slider to +500%.**

Vanilla caps brightness at 100% (`gamma:1.0`). Speedrun rules
explicitly permit 500%, and effectively every competitive player runs
it, because dark underwater ravines and nether structures are
otherwise very hard to read.

`alt` unlocks the slider so you can just set it: Options → Video
Settings → Brightness, drag to the right. It reads `+500%` at maximum.

Nothing is changed for you. The slider's ceiling is raised and that is
all — `alt` does not touch lighting, shadow rendering, or your setting.
Arriving on vanilla brightness is a real disadvantage against anyone
who knows to change it, so it is worth knowing this is allowed rather
than a grey area.

Fullbright resource packs, or anything pushing past 5.0 or altering
shadow rendering, are NOT permitted.

Editing `options.txt` by hand does not work and never did: Minecraft
clamps gamma back to 1.0 when it saves, so the change disappears the
next time you quit. Raising the slider's own maximum is what makes the
setting stick, which is why the mod does it that way.
