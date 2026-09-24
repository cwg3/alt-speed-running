# Running a test session

How to exercise the ladder with one human and a synthetic opponent.
Everything here is workflow, not guarantees — those live in `SPEC.md`.

## Fire the opponent, then queue

```bash
cd backend && bash scripts/pace-bot.sh 7200 0
```

`pace-bot.sh <finish-seconds> <lead-seconds>`. A 7200 finish means the
bot claims a two-hour run, so it never realistically wins and there is
no clock pressure. Use a shorter finish (e.g. `1200`) to test losing.

The bot gives up after about three minutes if nobody queues, so start
it when you are ready to play, not before.

**It polls the live endpoint every 15 seconds on purpose.** A real
client does, and that poll is the backend's heartbeat. A bot that only
posted splits looked abandoned between them and handed the human a win
it had not earned.

## Reset between runs

Forfeiting, dying or voiding leaves rating and seed state behind. To
put things back:

```bash
# rating and season points
aws dynamodb update-item --table-name <PlayersTable> \
  --key '{"uuid":{"S":"<your-uuid>"}}' \
  --update-expression "SET skillRating = :r, seasonPoints = :p REMOVE currentMatchId, splitStats" \
  --expression-attribute-values '{":r":{"N":"1500"},":p":{"N":"0"}}'

# free seeds held by dead matches (safe: refuses to free a live match's seed)
npx tsx scripts/releaseSeeds.ts <SeedPoolTable> <MatchesTable> --dry-run
```

`scripts/sweepAbandoned.ts` voids matches both players walked away
from and hands their seeds back.

## Testing the bad-seed vote

One player voting does nothing by design. To exercise the other half:

```bash
bash scripts/bot-bad-seed.sh          # makes PaceBot agree
npx tsx scripts/reviewBadSeeds.ts <SeedPoolTable>
```

Only vote on seeds that are genuinely unplayable. A vote quarantines
the seed permanently and shows up in the review list as evidence of a
filter bug — test votes on good seeds poison exactly the tool meant to
find real ones.

## Installing a new jar

**Close Minecraft first.** Swapping the jar under a running game has
caused `ClassNotFoundException` mid-match and killed a teardown.

```bash
cd mod && ./gradlew build
cp build/libs/speedrunmcalt-0.1.0.jar \
  ~/Library/Application\ Support/minecraft/mods/
```

Check the game is actually closed with
`pgrep -f net.minecraft.client.main.Main` — grepping for "minecraft"
also matches the Gradle daemon.

Compiling is not the same as working. Mixin signatures are validated
when the target class loads, so boot a server and look for
`InvalidInjection` before trusting a new mixin:

```bash
cd mod && rm -rf run/world && ./gradlew runServer --offline
```

## Seed type bias

`SEED_TYPE_BIAS` on the QueueJoin lambda restricts which types are
drawn. The CDK stack sets it, so **a deploy overwrites anything set by
hand** — a value set through the console once vanished silently and
the draws changed without anyone noticing.

It **must be empty before anyone but us plays**: an even mix across
five types is the point of having five.

## Watching a run

The client log is the only view of what the guarantees did:

```
~/Library/Application Support/minecraft/logs/latest.log
```

Follow it with `tail -F`, not `-f` — Minecraft replaces the file on
launch, and `-f` keeps watching the dead inode after a restart.

Lines worth watching: `Blacksmith at`, `Bastion centre`,
`Temple quiet zone`, `Flint schedule`, `Hoglin schedule`,
`Nether arrival`, `Shipwreck chests`, `Barter schedule`,
`Blaze rod schedule`, `Suspicious stew`.

## Don't run pool builds while someone is playing

World generation is CPU-hungry. Seven parallel workers once put a live
match 28 seconds behind. One serial worker is fine on a 10-core
machine; anything more should wait.

## Play-confirmed seeds

A seed that passed every filter has been verified by a program. A seed
in this list has been WALKED - somebody played the opening and said it
worked. That is a different claim and a stronger one, and it is the one
that has caught things the filters missed: a biome instead of a tree, a
portal that existed but could not be lit, a food chest under seven
blocks of stone.

Record the overworld and nether seed together. They are paired at load
time and a seed is only meaningful with its partner - "that seed was
good" means nothing if the nether came from somewhere else.

| type | overworld | nether | notes |
|------|-----------|--------|-------|
| desert_temple | seed#3fcb | seed#8037 | temple at 64,64. Overworld and nether both clean; two deaths were pace, not layout. |
| ruined_portal | seed#218e | - | portal at 64,224, bastion at -112,32 (bridge). Spawned near the bastion. |
| ruined_portal | seed#9796 | - | the first play-confirmed seed. Survived being wiped by a `--only` load and restored from the match record. |
