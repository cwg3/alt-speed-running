# alt

A 1v1 Minecraft 1.16.1 RSG speedrunning ladder. The premise is that
every deviation from vanilla is published, so the bar for honesty in
this repo is higher than usual, not lower.

## Never publish these

The pipeline is public. Its output is not. Four things stay out of git:

| out | why |
|---|---|
| **seed values** | a player who memorises the pool knows the world before it is dealt. The pool was purged from history for this |
| **filter yield rates** | together they say what a pool costs to build and which types are thin - a map of where the ladder is weak |
| **pipeline throughput** | the same information wearing a clock. "N pairs in M minutes" is a yield rate |
| **anti-cheat thresholds** | hands anyone faking a run the minimum that survives |

**A commit message is published.** It is on GitHub, in every clone, and
cannot be edited afterwards without rewriting history. On 2026-09-25 a
throughput figure was scrubbed out of SPEC.md and then typed into a
commit message and a code comment, and pushed. Scrubbing the file is
half the job.

The same goes for PR descriptions, issue text and code comments.

`tools/check-no-stats.sh` enforces this from `.githooks/pre-commit` and
`.githooks/commit-msg`. If it fires, read the lines it printed before
reaching for `ALT_ALLOW_STATS=1` - it is deliberately coarse, because a
false positive costs one override and a false negative is public
forever.

Measurements worth keeping go in a gitignored `*.local.json` beside
`backend/split-rules.local.json`, backed up to S3 under `secrets/`.

Deliberately still published: the vanilla probabilities in
DEVIATIONS.md. Quoting them is that file's entire function.

## Setup

`core.hooksPath` must point at `.githooks`, which a fresh clone does not
do on its own:

    git config core.hooksPath .githooks

## Naming

Checks have names, not numbers: `cubiomes`, `jigsaw`, `spawn`,
`village`, `ravine`, `portalfilter`, `nether`, `route`. Defined once in
SPEC.md's check table and dispatched by `seed-filter/run-check.sh`.
There were four disagreeing "stage N" schemes before; do not add a
fifth.

## Things that have bitten repeatedly

- **Mixin signatures validate at class load, not compile.** A clean
  build proves nothing. `mod/mixin-smoke.sh` is the check whose pass
  means the mod runs.
- **Classes under `com.speedrunmcalt.mixin.*` cannot be referenced from
  outside.** `mod/check-mixin-refs.sh` catches it. @Accessor/@Invoker
  interfaces are exempt.
- **Bash reads a script incrementally by byte offset.** Editing a
  running script corrupts the run in progress.
- **ERROR is not FAIL.** A crashed worker must never be read as a bad
  seed. Inconclusive rows stay held; that rule has saved the pool twice.
- **Cloud CSV shapes differ per check** - `nether` is seed-first,
  `route` is type-first. `run-check.sh`'s header says so in capitals.
- **`loadSeedPool.ts --only=<type>` DELETES that type's rows.** It reads
  like a filter and is not one. It discarded four verified drawable
  buried treasure seeds on 2026-09-25. Drawable rows now need `--yes`.
- **The loader restarts its nether index at 0 every run**, so repeated
  `--only` loads re-pair the same nether seeds; some are paired 2-3x.
  Reorder `nether_seeds.json` unused-first before a load, or fix the
  loader to track what is taken.
- **Docs written during a problem outlive the fix.** The gravel stat,
  the F6 forfeit note and the "results are committed" line all survived
  the change that made them false.
