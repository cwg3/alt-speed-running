#!/bin/bash
# PaceBot - a synthetic opponent that races a real player at a fixed pace.
#
# Exists because testing the losing side of a match needs a second
# player who reliably finishes, and the loss path (replay upload from
# the side that never reports a final split, rating loss, client
# teardown) is otherwise unreachable without two humans.
#
# Queues, waits to be matched with whoever else is in the queue, then
# reports splits on a schedule scaled to a target finish time.
#
#   bash scripts/pace-bot.sh [finish-seconds] [lead-seconds]
#     finish-seconds  the time the bot CLAIMS to finish in (default 240)
#     lead-seconds    fire each split this many seconds before it is due
#
# The lead exists to shorten a test without making the bot claim an
# implausible run: it moves only WHEN a split is sent, never the time it
# claims. Reporting a split late is always fine.
#
# The usable lead is small. The wall-clock tolerance was deliberately
# tightened from 60s to 10s after this bot demonstrated that 60s let it
# fake a full minute of progress on every split without flagging - so a
# lead at or above the tolerance now gets rejected. Splits also have
# plausibility floors (kill_dragon is 180s), so the shortest honest test
# is a little over 3 minutes of real time.
#
# NOTE: curl is called with --globoff and the body is built with printf
# into a variable. Building JSON inline inside a quoted string gets the
# escapes stripped, after which curl reads the body as a URL and brace-
# expands it on the commas - which silently sends several malformed
# requests instead of one good one.
set -u

API="${API:-https://4q2boikc88.execute-api.us-west-2.amazonaws.com}"
REGION="${REGION:-us-west-2}"
PLAYERS="${PLAYERS_TABLE:-BackendStack-PlayersTable70A03D78-1LMEI6GSB9FIU}"
SESSIONS="${SESSIONS_TABLE:-BackendStack-SessionsTable7C302024-77WDLRN5BZ7T}"
QUEUE="${QUEUE_TABLE:-BackendStack-QueueTable4C3A1E0F-BI6XYSUC1HRJ}"

# Must match WorldSetupVersion.CURRENT in the mod.
#
# The bot never calls /auth/verify - it writes its own session row - so
# nothing sets this for it the way a real login does. Matchmaking now
# refuses to pair players whose world-building rules differ, so leaving
# it unset means the bot silently never matches anybody.
WORLD_SETUP_VERSION="${WORLD_SETUP_VERSION:-1}"

FINISH="${1:-240}"
LEAD="${2:-0}"
# Must stay under the server's WALL_CLOCK_TOLERANCE_MS (10s).
if [ "$LEAD" -ge 9 ]; then
  echo "lead must be under 9s - the server's wall-clock tolerance is 10s" >&2
  exit 1
fi
UUID="bot-rival"
TOK="bot-tok-rival"
NAME="PaceBot"

# Split schedule as a fraction of the finish time, taken from the shape
# of a real sub-10 RSG run rather than spaced evenly - an even spread
# trips the plausibility floors.
SPLITS=(enter_nether:0.167 piglin_barter:0.250 obtain_rod:0.417 \
        enter_stronghold:0.708 enter_end:0.833 kill_dragon:1.0)

cleanup() {
  aws dynamodb delete-item --region "$REGION" --table-name "$QUEUE" \
    --key "{\"uuid\":{\"S\":\"$UUID\"}}" >/dev/null 2>&1
}
trap cleanup EXIT

now=$(date +%s); now_ms=$((now*1000)); exp=$((now+7200))

# Refresh the bot's player row and session each run. Rating is pinned to
# 1500 so the skill-range matcher always considers it a fair pairing.
# update-item, not put-item. put-item REPLACES the row, and the bot's
# row now carries seenSeeds - the set of pairs it has already played.
# Overwriting it reset the bot to having seen nothing every run, which
# quietly made it useless as a test partner: the draw is supposed to
# exclude seeds EITHER player has seen, and a bot with an empty set
# never constrains it. Mirrors what verifySession does for real
# players, which was always an update and was never affected.
aws dynamodb update-item --region "$REGION" --table-name "$PLAYERS" \
  --key "{\"uuid\":{\"S\":\"$UUID\"}}" \
  --update-expression "SET username = :n, lastLoginAt = :t, createdAt = if_not_exists(createdAt, :t), skillRating = if_not_exists(skillRating, :r), seasonPoints = if_not_exists(seasonPoints, :z), worldSetupVersion = :w" \
  --expression-attribute-values "{\":n\":{\"S\":\"$NAME\"},\":t\":{\"N\":\"$now_ms\"},\":r\":{\"N\":\"1500\"},\":z\":{\"N\":\"0\"},\":w\":{\"N\":\"$WORLD_SETUP_VERSION\"}}" >/dev/null
aws dynamodb put-item --region "$REGION" --table-name "$SESSIONS" --item \
  "{\"token\":{\"S\":\"$TOK\"},\"uuid\":{\"S\":\"$UUID\"},\"createdAt\":{\"N\":\"$now_ms\"},\"expiresAt\":{\"N\":\"$exp\"}}" >/dev/null

echo "PaceBot waiting for an opponent (will finish at $((FINISH/60)):$(printf '%02d' $((FINISH%60))))..."

MATCH_ID=""
OPPONENT=""
for _ in $(seq 1 60); do
  RESP=$(curl -s --globoff -X POST "$API/queue/join" -H "Authorization: Bearer $TOK")
  MATCHED=$(python3 -c "import json,sys;print(json.load(sys.stdin).get('matched',False))" <<<"$RESP" 2>/dev/null)
  if [ "$MATCHED" = "True" ]; then
    MATCH_ID=$(python3 -c "import json,sys;print(json.load(sys.stdin)['matchId'])" <<<"$RESP")
    # Where the match's structure is, so the synthetic trace can run
    # near it. Starting at the world origin put the bot's whole run
    # hundreds of blocks from where the match happened - drawn
    # faithfully in a replay, and far too far away to see.
    STRUCT_X=$(python3 -c "import json,sys;print(json.load(sys.stdin).get('structureX',0))" <<<"$RESP")
    STRUCT_Z=$(python3 -c "import json,sys;print(json.load(sys.stdin).get('structureZ',0))" <<<"$RESP")
    OPPONENT=$(python3 -c "import json,sys;print(json.load(sys.stdin)['opponent']['username'])" <<<"$RESP")
    break
  fi
  sleep 3
done

if [ -z "$MATCH_ID" ]; then
  echo "No opponent found - is the other client queued?"
  exit 1
fi

echo "Matched vs $OPPONENT - matchId=$MATCH_ID"
START=$(date +%s)

for entry in "${SPLITS[@]}"; do
  name="${entry%%:*}"; frac="${entry##*:}"
  at=$(python3 -c "print(int($FINISH * $frac))")
  # Claimed time stays at $at; only the moment we send it moves earlier.
  due=$(( at - LEAD )); [ "$due" -lt 0 ] && due=0

  # Sleep until this split is due, measured from the start rather than
  # accumulating sleeps, so request latency doesn't drift the schedule.
  #
  # Polls the live endpoint while it waits, because a real client does
  # - every few seconds, all match long. That poll is the backend's
  # heartbeat, and a bot that only posted splits looked ABANDONED
  # between them: at a 2-hour pace its first split is 20 minutes out,
  # so the ten-minute abandonment clock expired and handed the human
  # a win they had not earned, complete with +16 rating.
  #
  # The backend was right and the test double was wrong. A stand-in
  # that does not make the calls the real thing makes will eventually
  # prove something false.
  POLLED=0
  while :; do
    elapsed=$(( $(date +%s) - START ))
    [ "$elapsed" -ge "$due" ] && break
    if [ $(( elapsed - POLLED )) -ge 15 ]; then
      curl -s -o /dev/null --globoff -X GET "$API/matches/$MATCH_ID/live" \
        -H "Authorization: Bearer $TOK" || true
      POLLED=$elapsed
    fi
    sleep 1
  done

  ms=$((at*1000))
  BODY=$(printf '{"matchId":"%s","splitName":"%s","elapsedMs":%d}' "$MATCH_ID" "$name" "$ms")
  OUT=$(curl -s --globoff -X POST "$API/matches/split" \
    -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
    -d "$BODY")
  echo "[$(date +%H:%M:%S)] $name claimed ${at}s (sent at ${due}s) -> $OUT"
done

# Upload a synthetic replay trace.
#
# The bot posts splits but never recorded a position trace, so every
# replay in the system was single-perspective and the dual-perspective
# playback had nothing to test against. This gives the bot a second
# trace per match.
#
# Generated to be PLAUSIBLE, not random: in the right dimension when
# each split is claimed, moving at speeds replayChecks accepts, looking
# roughly where it is going. Verified against checkReplay itself -
# a fixture that trips the anti-cheat would be useless for testing the
# real upload path.
if [ -n "${MATCH_ID:-}" ]; then
  TRACE=$(npx tsx "$(dirname "$0")/synthTrace.ts" "$FINISH" 10 \
      "${STRUCT_X:-0}" "${STRUCT_Z:-0}" 2>/dev/null)
  if [ -n "$TRACE" ]; then
    BODY=$(printf '{"matchId":"%s","samples":%s}' "$MATCH_ID" "$TRACE")
    RESP=$(curl -s --globoff -X POST "$API/matches/replay" \
      -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
      -d "$BODY")
    echo "replay upload -> ${RESP:0:120}"
  else
    echo "replay upload skipped - synthTrace produced nothing" >&2
  fi
fi

echo "PaceBot finished - $OPPONENT should see DEFEAT, then the result screen"
