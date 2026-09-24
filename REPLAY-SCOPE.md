# Replay playback - scope

Goal: after a match, either player can watch the run back in 3D - real
terrain, real structures, positions and times, and what was picked up
and when.

## Why this is cheap for us

We own the seed, and **the match world is exactly reproducible**. Every
placement draws from `new Random(seed ^ SALT ^ position)` - lava pools,
portal obsidian, loot top-ups, bastion loot, shipwreck chests. Nothing
touches wall-clock time. That determinism exists because both players
must get identical worlds; it means a replay needs no world data at
all.

    seed + trace  ->  regenerate the world, fly a camera along the path

Storing the world would be gigabytes. Storing nothing costs nothing.

## What is already there

- `ReplayRecorder` samples `{t, dim, x, y, z}` every 20 ticks
- `POST /matches/replay` gzips to a private S3 bucket, `{matchId}/{uuid}.json.gz`
- The match record keeps `overworldSeed`, `netherSeed`, `seedType`, `players`
- Participant check on upload already exists

## What is missing

### 1. Richer recording

| change | why |
|---|---|
| 20 ticks -> 2 ticks (10Hz) | 1Hz is a slideshow; a camera needs smooth motion |
| add `yaw`, `pitch` | where they were LOOKING; not recorded at all today |
| add pickup events | "see loot drops" - item, count, t, position |

Size: 10Hz for a 10-minute run is ~6000 samples. With rotation and
gzip, roughly 300-500KB per run against today's ~50KB. Still trivial
next to video, but ten times the storage - worth deciding deliberately
rather than drifting into.

`MAX_SAMPLES` must rise with the rate or a long run silently truncates.

### 2. Retrieval

- `GET /matches/{matchId}/replay?player=<uuid>`
- `replayBucket.grantRead` on that function - it has `grantPut` only
- Participant check, mirroring upload

### 3. Playback

- Regenerate the world from the match record, reusing `MatchWorldCreator`
- Spectator camera driven along the trace, interpolated between samples
- Timeline: scrub, pause, speed, jump to a split
- Switch between the two players' paths, or show both

## The feature set to match

Taken from a description of what the incumbent's replays do. These are
functional requirements, and they change two things above.

| feature | what it needs from us |
|---|---|
| **Dual perspectives** - switch freely between both players' FIRST-PERSON views | BOTH traces client-side, and yaw/pitch, which we do not record at all |
| **Both players visible in the world** | the other player rendered as an entity driven by their trace, not just a camera path |
| **Timeline bar on Escape**, colour-coded by dimension | already supported - every sample carries `dim` |
| click the bar to scrub instantly | seek to an arbitrary t; trace is already time-indexed |
| **Free spectator flight**, or lock the camera to a player | camera mode toggle over the same data |
| pause, play, arrow keys to jump, playback speed | playback clock decoupled from the world tick |
| **name visibility, player opacity, glowing distance** | render options on the rendered opponent |

Two consequences worth naming:

**First-person is the requirement, not a nicety.** A path with no
rotation can only ever be a floating camera. Recording yaw and pitch
moves from "would be nice" to load-bearing, and so does the sample
rate - a first-person view at 1Hz is unwatchable in a way a
third-person path is not.

**Both players are rendered, so both traces must be fetched.** That
settles part of the access question below: studying the opponent IS
the feature, so participants necessarily see each other's replays.
What stays open is whether anyone ELSE can.

**The dimension colouring is free.** We already store `dim` per
sample, so overworld/nether/end banding on the timeline needs no
recording change - a small piece of luck from a field added for
verification.

## Getting to a replay, and keeping one

| behaviour | what it needs from us |
|---|---|
| **Profile > Matches**, click a match, Watch Replay | a MATCH HISTORY screen, which does not exist at all |
| **"My Replays"** - download one to keep it permanently | local storage, and a server-side expiry for everything else |
| playback speed and name toggles on a second menu page | a settings layer over playback |
| replays only for real matchmaking, not private rooms | nothing yet - we have no private rooms |

**Match history is a prerequisite, not part of the replay work.** There
is no screen listing a player's past matches, and no endpoint behind
one. A replay nobody can navigate to is not a feature. That is its own
piece of work and it should land first.

**Download-to-keep answers the storage question** in the section below.
Keep every replay for a window, let a player pin the ones they care
about, expire the rest. The permanent record stays cheap and the
player keeps their best games and worst losses. That is a better
answer than the downsampling idea below, because it puts the choice
with the person who knows which matches mattered.

## Decisions needed before building

**Who can watch?** Participants only is safe: under the seen-seeds rule
neither can ever draw that seed again. A **publicly shareable** replay
is not - it shows a third party, who CAN still be dealt that seed,
where the structure, portal and bastion are. Easier to design out now
than retract later.

**Storage growth.** Ten times per-run size, kept forever, is a bill
that arrives quietly. A lifecycle rule - full-rate trace for N days,
then downsample to 1Hz for the verification record - keeps the
audit story without keeping the camera data.

**Version drift, the non-obvious one.** A replay regenerates the world
with TODAY's setup code. Change `LavaPoolPlacer` and every old replay
silently regenerates a world the player never saw - lava in different
places, different loot. The trace would be truthful and the world
around it wrong, which is worse than having no replay.

So the match record needs a **world-setup version**, written at match
time, and playback must refuse - or clearly warn - when it does not
match the running build. This also means the setup code becomes
versioned surface that cannot be freely changed, which is a real
constraint worth accepting knowingly.

## Rough order

1. Version stamp on the match record - needed regardless, cheap, and
   worthless if added after replays exist
2. Recorder: rate, rotation, pickups
3. GET endpoint and read permission
4. Playback screen

1 and 2 change what is recorded, so they want to land before anyone
accumulates replays worth keeping.
