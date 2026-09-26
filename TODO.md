# TODO

Known outstanding work, roughly in the order it would matter.

## Operational

- [x] **`verify-and-release.sh` renamed too.** It was mid-run when the
  rest of the rename happened, so it had to wait for the job to exit.

- [x] **The four cloud paths have now run**, and three were broken -
  which was the whole worry. `run-check.sh` CLOUD=1 passed no instance
  type, so every check silently refused to launch; the `route` join read
  the wrong column and discarded all 188 rows it was given; and the role
  check read "cannot see it" as "not there". All fixed, all exercised
  against real batches.

- [x] **The nightly top-up schedule is installed.** `alt-pool-topup`
  fires at 03:00 UTC, `cron(00 03 * * ? *)`, targeting
  `ec2:runInstances`, with `alt-topup-runner`, `alt-topup-scheduler` and
  `alt-seedwork` in place. Confirmed against the scheduler itself on
  2026-09-26 rather than inferred from having run the install script,
  and it has demonstrably fired unattended: a runner instance launched
  at 03:00:10 UTC that morning, work instances followed it, and every
  one of them terminated rather than being left running.

  This item read "NOT installed" until then, which is the drift it warns
  about everywhere else in this file - the schedule went in and the line
  describing it stayed put. What caught it was `tools/backup.sh`, which
  captures the schedule and its roles as AWS currently has them. That is
  exactly the question the bundle exists to answer, and the reason to
  back up what is running rather than only what can be rebuilt.

- [ ] **Per-type candidate headroom.** `HEADROOM` is one number for
  every type, and the types differ enormously in how many candidates
  survive. A shipwreck batch loses most of its candidates at the `spawn`
  check because ocean spawns are where a wooded biome is technically
  near and practically unreachable; ruined portal loses most of its at
  the frame check. One multiplier cannot serve both, so the thin types
  stay thin and the next night tries again.
- [ ] **Release assets go stale silently.** It has now happened twice:
  v0.1.0's jar predated the fountain ending, and then predated the
  leaderboard by a day. Both times the code was pushed and the download
  was not. Rebuild and re-upload as part of publishing, and check the
  packed jar actually contains the classes you expect rather than
  trusting that the build ran — `pack/build-pack.sh` smoke-tests the jar
  before packaging, which is the only reason this is a staleness problem
  and not a broken-download one.

  0.1.1 was built and uploaded as part of publishing, from the commit it
  names, which is the habit this item is asking for. The version was
  bumped rather than rebuilt over 0.1.0 - two different builds under one
  number is the failure a version exists to prevent.

- [ ] **Backups were run by hand and stopped happening.** The last one
  predated the leaderboard, the ladder reset, the publish guard and the
  whole top-up pipeline. `tools/backup.sh` now does it in one command —
  tables, replays, the gitignored `*.local.json` files and the pack. Run
  it after anything that changes the pool or the schema.
- [ ] **`backend/split-rules.local.json` is not in git.** It holds the
  anti-cheat thresholds and is read at `cdk synth` time. Losing it does
  not break a deploy - checking silently degrades to loose defaults and
  `cdk synth` warns - so it fails quietly. Copy is in the backup bundle
  under `secrets/`. Any new machine needs it before its first deploy.

## Mods

- [ ] **Turn the queue-join gate on.** It is BUILT and switched off:
  `lib/modRules.ts` holds the list as data, `queueJoin` returns a 403
  naming the mods, and `MOD_GATE_ENABLED` defaults to false. Two things
  have to happen first, and neither is code:

  1. **Read the recorded lists.** Nobody has looked at what real clients
     carry, which is what step 1 was for. The list was written from
     first principles and one runner's judgement.
  2. **Confirm MCSR Fairplay's mod id.** README calls it legal and
     `ALLOWED_MODS` does not contain it, because the id has never been
     read off an install and an allowlist entry that never matches
     refuses a legal mod in silence. A test asserts its absence so a
     guessed id cannot be slipped in; update both together.

  Enabling it is then one environment variable and a deploy. Signed
  attestation stays off the list entirely - SPEC.md says why.

- [ ] **`tools/pack-modules.sh` has to be re-run when Fabric API moves.**
  `PACK_MODULES` is generated from the pinned jar, and an upstream rename
  or addition would read as an unknown mod to the gate. Tied to
  `fabric_api_version` in `mod/gradle.properties`.

- [ ] **The whitelist should be backend data, not README prose.** A
  list change currently cannot reach anyone without a client release,
  and nothing serves the list at all. `split-rules` is the precedent,
  including the part worth copying: absent config degrades to loose,
  never to strict. Unlike the split floors this list is public, so it
  can live in the repo and be served from there.

- [x] **A mod list has now been seen in a live match.** 2026-09-25, on a
  PaceBot match: `PaceBot: mods not recorded` above
  `MissVanFan: nothing beyond the pack`. Login, recording, carry-through
  and both display branches, confirmed against a real client - and
  `beyondPack` filtered a real loader's 39 Fabric API modules with no
  prefix rule.

- [ ] **The branch that prints actual mod names has never rendered.**
  Every list seen so far is empty or absent. It needs a tester running
  something legitimate beyond the pack - Sodium is the obvious one - and
  two real players on 0.1.1 for two populated lists side by side. A bot
  cannot supply either, because it has no list at all.

- [ ] **MCSR Fairplay is named in the whitelist but untested here.** It
  is the incumbent's tool, which is a real dependency: if theirs moves,
  our rule moves with it. Shipping our own pack check would remove
  that, and is not a reason to leave players with nothing meanwhile.

## Shipped

- [x] **The two-player integration test passes again.** It had been
  failing 8 of 20 against the deployed backend, and not one of those was
  a backend bug: it posted `/matches/complete` with no splits behind it,
  so the endpoint refused with a 409 - a kill_dragon split is the
  precondition that stops a client claiming a win it never ran - and the
  match stayed pending, which failed six more assertions downstream plus
  the ghost-queue-row check, where the `matched: true` was the REJOIN
  path answering for a match that never finished.

  The test also sent no `elapsedMs`, so it could not have settled the
  provisional close-finish hold, and it piped the response to
  `/dev/null`, so it reported "still pending" instead of the refusal it
  was being handed. It now runs Bob's route, settles the hold on the
  window the server names, and keeps every body so a failure says why.
  22 of 22.

  **The test outlived the features it was testing.** Same shape as the
  stale docs this file keeps warning about, one layer down - and two
  comments were stale with it: `completeMatch.ts` still said a match
  auto-completes from a kill_dragon split, which is what the test
  believed, and `releaseSeeds.ts` still described seeds as consumed on
  claim, which is why running the test was thought to cost a pool seed.
  Both corrected. A test that passes for stale reasons is a test nobody
  can read a regression out of.

  It also cleans up its history rows now. Those only began to exist once
  completion worked, so nothing had ever cleaned them and two `tpt-` rows
  were sitting in the real history table. `splitStats` needs no cleanup -
  it is written onto the player row, which the script deletes - and that
  must stay true, because a synthetic run training a real anti-cheat
  baseline is what the ladder reset existed to undo.

- [x] **The mod list is recorded and shown to both players.** `ModList`
  collects every loaded mod at login - Fabric cannot load one after
  startup without a relaunch, which is another login - and it travels
  the path `worldSetupVersion` already takes: player row, queue row,
  match row, both detail screens. Nothing is gated on it.

  An ABSENT list stays distinct from an EMPTY one the whole way to the
  screen, which reads "mods not recorded" rather than "nothing beyond
  the pack". No evidence and exculpatory evidence are different claims.
  `sanitizeModList` returns undefined rather than `[]` for that reason,
  and a test pins it along with the caps - `/auth/verify` is public, and
  this field arrives before Mojang has said who is calling.

  The display hides what the pack installs by asking the loader which
  modules are nested inside Fabric API, not by matching names: a
  `fabric-` prefix rule would hide anything calling itself
  fabric-whatever. Shipped in 0.1.1.

- [x] **The mod whitelist is published.** README carries the list
  (Sodium, Lithium, Starlight, SpeedRunIGT, MCSR Fairplay), the
  principle behind it - legal if it changes how the game is drawn or how
  fast it runs, not legal if it changes game state, reveals what the seed
  did not give you, or automates input - and an issue anyone can open to
  argue a mod onto it, answered in public with a reason.

  A whitelist rather than a banned list, which only ever names the
  cheats somebody already thought of. A list nobody can petition is an
  allowlist with better manners, which is the thing this project objects
  to.

- [x] **Leaderboard.** `GET /leaderboard`, public, ranked by rating with
  season points and a W-L-F record. Bots race but do not rank, and the
  screen says how many were hidden rather than dropping rows silently.
  Win/loss/forfeit counters live on the player row.

- [x] **The ladder was reset** on 2026-09-25, before any tester saw it.
  The old rating had been moved by matches nobody played, against an
  opponent that is not ranked. Everyone starts at 1500 with an empty
  history.

- [x] **A guard against publishing pool statistics.**
  `tools/check-no-stats.sh`, from both pre-commit and commit-msg. A
  commit message is published as surely as a file is, which is how a
  throughput figure reached GitHub after being scrubbed from the docs.

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

- [ ] **Pool REPLENISHMENT, not pool depth.** The framing that
  matters: a strong runner averages about 9 minutes, which is roughly
  5 matches an hour once replays and downtime are counted, over a 4-5
  hour session. Call it 25 matches a day at full tilt.

  A seed is consumed per PLAYER: both players in a match mark it seen,
  and it is dead for both of them afterwards even though a different
  pair can still draw it. So one seed covers two player-consumptions,
  and the requirement is LINEAR in player count:

      N players x 25 matches = 25N player-consumptions a day
      each seed covers 2     -> 12.5 x N seeds a day

      N=2 -> 25/day      N=4 -> 50/day      N=10 -> 125/day

  25 per player is a safe upper bound - the real figure is half that,
  because you and your opponent burn the same seed in the same match.
  At two players the two numbers coincide, which is what made an
  earlier 'it scales sublinearly' reading look correct.

  Against that, a 70-seed pool is about three days for one grinder.

  Production is not the constraint: 620 land candidates took about 75
  minutes on one spot instance. Buried treasure is, because the
  two-magma-ravine check is where the yield collapses - a grinder
  would exhaust BT first and watch it silently leave their draw while
  the other four types kept working.

  So the work is a repeatable top-up, not one big batch.

  This matters more now than it did yesterday: with the release out,
  more than one person will be drawing from the same pool.

## Housekeeping

- [x] **One name per check, no stage numbers.** There were four
  numbering schemes and they disagreed: `stage 3` meant the type checks
  in `overnight-rebuild.sh`, the magma ravine in `build-pool.sh`, and
  the spawn check in `ocean-rebuild.sh`, while SPEC.md counted five
  "tiers" that lined up with none of them. Renumbering would not have
  fixed it, because the numbers were the problem - a position in one
  script's sequence is not an identity. Every check is now referred to
  by the name `run-check.sh` already dispatched it under (`cubiomes`,
  `jigsaw`, `spawn`, `village`, `ravine`, `portalfilter`, `nether`,
  `route`), defined once in SPEC.md's check table. The two steps that
  are not checks - load HELD and release - say so instead of taking a
  number. Do not reintroduce numbered stages.

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

## Decided

- [x] **Eligibility is narrow on purpose.** Anyone with a legitimate
  Minecraft account can play. Bans elsewhere, past conduct and
  controversy are not considered; only cheating here removes you. It is
  written down (README, "Who can play") so it is a rule that predates
  any particular person rather than a decision made about one — which is
  the difference between applying a policy and picking a side.

- [x] **Conduct is a standard, not a word list.** "Don't say anything
  you wouldn't say to their face." Not a rule against aggression - trash
  talk is part of competition - but against being anonymous about it.
  Word lists get lawyered; a standard asks a question people already
  know the answer to.

## Deferred by decision

- [ ] **Match chat** — the policy now exists (README, "How to behave"),
  so this is waiting on the three structural decisions that go with it,
  which must be settled BEFORE chat ships rather than retrofitted:

  1. **Messages attach to the match record**, not to a session. The
     arcade property being rebuilt is attribution: a report should be
     "look at the match", not one word against another. Ephemeral chat
     cannot be reviewed, so it cannot be enforced.
  2. **No DMs and no friends list.** Contact exists only inside a match
     both players are in. Following someone home from a loss is where
     most real harm online happens, and not building the surface is
     cheaper than policing it.
  3. **Retention.** Replays already have no expiry; chat attached to a
     match inherits that. Decide deliberately whether it should, because
     "forever" is a choice even when it is the default.

  Also needs the enforcement path the policy promises: a human looks, the
  reason is given in plain English, and it can be argued with. Same shape
  as the anti-cheat flags, and it should reuse that machinery rather than
  grow a second one.
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
