# TODO

Known outstanding work, roughly in the order it would matter.

## Operational

- [ ] **Four cloud paths have never actually run.** Written tonight,
  logic-tested locally, but no instance has executed them:
  `run-check.sh` CLOUD=1, `verify-and-release.sh` CLOUD=1,
  `CHECK=village`, and the `CHECK=route` seed-field fix. The route bug
  they were written alongside is exactly this shape - a second path
  nobody exercises stays broken until someone uses it. The current
  pool batch exercises all four; if it is abandoned, they are still
  unproven.
- [ ] **Release assets go stale silently.** v0.1.0's jar was built
  hours before it was published and predated the fountain ending — a
  tester on it would have killed the dragon and waited forever for a
  result the backend no longer sends. Rebuild and re-upload as part of
  publishing, and check the packed jar actually contains the mixins
  you expect rather than trusting that the build ran.
- [ ] **`backend/split-rules.local.json` is not in git.** It holds the
  anti-cheat thresholds and is read at `cdk synth` time. Losing it does
  not break a deploy - checking silently degrades to loose defaults and
  `cdk synth` warns - so it fails quietly. Copy is in the backup bundle
  under `secrets/`. Any new machine needs it before its first deploy.

## Shipped

- [x] **The repo is public** and **v0.1.0 is released** — the pack,
  the mod jar and Fabric API are downloadable. Login is still
  invite-only, so the allowlist is now the only access control and is
  doing real work rather than sitting behind a private repo.
- [x] **Social preview image** uploaded. `brand/social_preview.png`,
  regenerate with `brand/gen_social.py`.

  Worth knowing for next time: the Social preview section does not
  exist on a PRIVATE repository, so it cannot be done before going
  public. It appears in Settings → General once the repo is public.
  Web UI only; no API.

## Before testers

- [ ] **Pool depth.** The one thing still genuinely short. A player
  never draws the same seed twice, so a thin type lasts as many
  matches as it has seeds and then silently drops out of that player's
  draw. Land types are cheap to build; ocean types need the
  two-magma-ravine check and cost considerably more.

  This matters more now than it did yesterday: with the release out,
  more than one person will be drawing from the same pool.

## Housekeeping

- [x] **Match worlds are cleaned up.** `MatchWorlds` keeps the three
  most recent plus the live one, trimming a few per match off the game
  thread. The current match is always spared — a player who crashes
  mid-run rejoins the SAME world and needs it intact. The shared
  delete lives in `world/GeneratedWorlds`, used by both this and the
  replay cleaner.
- [x] **"Experimental settings" warning.** Documented in INSTALL.md
  rather than silenced — the warning is accurate, and a project that
  publishes every deviation should explain an accurate warning instead
  of suppressing it. Revisit only if testers report it as confusing
  despite the note.

## Kept out of the repository

Done, and recorded here so none of it gets quietly undone. The
pipeline is public; its output is not.

- [x] **The seed pool.** Purged from every commit. Publishing it would
  let a player memorise the worlds they are about to be dealt, which
  defeats the rule that nobody draws the same seed twice. Nine
  drawable seeds had been quoted in the docs with their coordinates;
  seeds now appear as tags like `seed#3fcb`.
- [x] **Anti-cheat thresholds.** In `split-rules.local.json`, not the
  repo. What is checked stays published; the numbers would hand anyone
  faking a run the minimum that survives.
- [x] **Filter yield rates.** Removed from SPEC, TODO and the scripts.
  Together they said what a pool costs to build and which types are
  expensive. The reasoning was kept, the numbers were not.
- [x] **A reference to the naming scrub**, which pointed at the thing
  the scrub existed to avoid drawing attention to.

Deliberately still published: the vanilla probabilities in
DEVIATIONS.md. Those are public Minecraft facts, and quoting them is
the entire function of that file.

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
