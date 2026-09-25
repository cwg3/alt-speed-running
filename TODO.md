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
  and then silently drops out of their draw. Land types are cheap to
  build; ocean types need the two-magma-ravine check and cost
  considerably more.
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

## We need your help! 🎣

Four deviations are documented but have never been caught happening in
a real match. Everything else on this ladder has been confirmed in
play; these four have not, and we would rather say so than quietly
leave them on the list.

**If you catch one, tell us.** A screenshot, a clip, or the match ID
is plenty. This is the most useful thing a tester can do right now,
and it is the sort of thing that only turns up when a lot of people
are playing rather than one person hunting for it.

One catch: **none of these do anything in a practice world.** Every
one is inert unless you are in a real match — the code checks, and
leaves practice worlds completely vanilla. So it has to happen in a
match to count.

The other catch is that Minecraft is random, so "I saw the thing" is
not quite enough on its own. What we need is an observation vanilla
cannot also explain:

- [ ] **Hoglin drops** — a match gives you 24 porkchops across 8
  kills, always, and your opponent gets the same. Vanilla drops 2–4
  per kill and averages the same over eight, so a single kill proves
  nothing: 3 is vanilla's commonest roll. **Eight kills totalling
  exactly 24** is the tell.
- [ ] **Suspicious stew** — only *harmful* effects are stripped, and
  most flowers give harmless ones anyway. So eating stew and being
  fine is not evidence. What we need is a stew that *should* have
  hurt — brewed from a wither rose or a lily of the valley — eaten in
  a match, with no wither or poison applied.
- [ ] **Drowned tridents**
- [ ] **Wither skeleton crowding**

Two of these have already been reported as seen, and both turned out
to be equally explained by vanilla. No harm done — that is exactly how
this is supposed to work, and we would rather chase a few false
positives than mark something confirmed that is not.
