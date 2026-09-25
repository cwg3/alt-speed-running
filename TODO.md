# TODO

Known outstanding work, roughly in the order it would matter.

## Durability

- [ ] **Versioning on the replay bucket.** S3 replay objects have no
  versioning, so a script that deleted them would be unrecoverable
  beyond the last manual snapshot. Low risk — nothing deletes replays
  today — but it is the one asset without a second line of defence.
  The DynamoDB tables got point-in-time recovery; this is the same
  argument applied to the bucket. Add `versioned: true` to the
  `ReplayBucket` in `backend/lib/backend-stack.ts`.

## Before testers

- [ ] **Repo visibility.** Private, so the draft GitHub release is not
  downloadable by anyone invited. Going public exposes source, SPEC,
  DEVIATIONS and full history — history was already rewritten for the
  naming scrub, so it is clean.
- [ ] **Pool depth.** Thin types run out fast: a player never draws
  the same seed twice, so a type with four seeds lasts four matches
  and then silently drops out of their draw. Land types are cheap
  (~85 candidates, ~20 min, ~$1, measured 95% spawn pass rate); ocean
  types need the two-magma-ravine check and cost ~950 candidates and
  ~2h for buried treasure.
- [ ] **Social preview image.** GitHub web UI only — no API for it.

## Housekeeping

- [ ] **Match worlds are never cleaned up.** Every match leaves ~40MB
  on disk forever. Replay worlds got this treatment already
  (`ReplayWorlds`); match worlds did not.
- [ ] **"Experimental settings" warning.** Loading a match world
  manually from the world list warns, because `MatchWorldCreator`
  marks its custom dimension registry `Lifecycle.experimental()`.
  Never seen during an actual match. Silencing it would suppress a
  warning that is arguably telling the truth.

## Deferred by decision

- [ ] **Match chat** — waiting on a moderation policy.
- [ ] **Download Replay / "My Replays"** — scoped in `REPLAY-SCOPE.md`,
  never built. Replays live server-side with no expiry.
- [ ] **Spawn events in replays** — deliberately not built. Every mob
  spawn across loaded chunks is hundreds a minute and would bury the
  position data it is meant to annotate. What a viewer actually wants
  to know — what killed me, and where did it come from — the death
  event and the entity track already answer.

## Unverified in play

Four deviations are documented but have never actually fired in a
match, which is a bad combination for a project whose pitch is that
every deviation is published:

- [ ] hoglin drops (needs a player-credited melee kill)
- [ ] suspicious stew
- [ ] drowned tridents
- [ ] wither skeleton crowding
