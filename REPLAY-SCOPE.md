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
