# TODO

Known outstanding work, roughly in the order it would matter.

## Operational

- [ ] **`backend/split-rules.local.json` is not in git.** It holds the
  anti-cheat thresholds and is read at `cdk synth` time. Losing it does
  not break a deploy - checking silently degrades to loose defaults and
  `cdk synth` warns - so it fails quietly. Copy is in the backup bundle
  under `secrets/`. Any new machine needs it before its first deploy.

## Before testers

- [ ] **Repo visibility.** Private, so the draft GitHub release is not
  downloadable by anyone invited.
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
- [x] **"Experimental settings" warning.** Documented in INSTALL.md
  rather than silenced — the warning is accurate, and a project that
  publishes every deviation should explain an accurate warning instead
  of suppressing it. Revisit only if testers report it as confusing
  despite the note.

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

Each needs an observation vanilla cannot also explain. Casual
sightings do not count, and two have already been mistaken for
confirmation:

- [ ] **hoglin drops** — the schedule is 24 porkchops across 8 kills
  IN A MATCH. Vanilla drops 2-4 per kill and averages the same over
  eight, so single kills prove nothing: 3 is vanilla's commonest roll.
  Eight kills totalling exactly 24 is the tell. The mixin also logs
  `Hoglin schedule: N porkchops and M hides per K kills` when it runs,
  which is the cheaper check.
- [ ] **suspicious stew** — only HARMFUL effects are stripped, and
  most flowers give benign ones. Eating stew without being hurt is not
  evidence. Proof needs a stew brewed from a wither rose or a lily of
  the valley, eaten in a match, with no wither or poison applied.
- [ ] **drowned tridents**
- [ ] **wither skeleton crowding**

Note that every one of these is inert in a practice world by design -
the mixins return early when `MatchState.inMatch()` is false - so any
test has to happen inside a real match.
