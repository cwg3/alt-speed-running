#!/bin/bash
# Integration test for the two-player matchmaking handshake against the
# deployed backend.
#
# The case that matters: only the player whose poll *finds* an opponent
# receives matched:true. The other player must still discover the match
# on their next poll. Before that was fixed, whoever polled second
# stranded the other player in the queue forever while the match sat
# pending - which is exactly what two real humans would hit.
#
# Creates its own throwaway players and cleans up after itself.
set -u

API="${API:-https://4q2boikc88.execute-api.us-west-2.amazonaws.com}"
REGION="${REGION:-us-west-2}"
PLAYERS="${PLAYERS_TABLE:-BackendStack-PlayersTable70A03D78-1LMEI6GSB9FIU}"
SESSIONS="${SESSIONS_TABLE:-BackendStack-SessionsTable7C302024-77WDLRN5BZ7T}"
QUEUE="${QUEUE_TABLE:-BackendStack-QueueTable4C3A1E0F-BI6XYSUC1HRJ}"
MATCHES="${MATCHES_TABLE:-BackendStack-MatchesTable36E59E86-2WG7P01DU5BP}"

A_UUID="tpt-alice"; A_TOK="tpt-tok-alice"
B_UUID="tpt-bob";   B_TOK="tpt-tok-bob"

pass=0; fail=0
check() { # check <description> <actual> <expected>
  if [ "$2" = "$3" ]; then
    echo "  PASS  $1"
    pass=$((pass+1))
  else
    echo "  FAIL  $1 (got '$2', expected '$3')"
    fail=$((fail+1))
  fi
}

cleanup() {
  for u in "$A_UUID" "$B_UUID"; do
    aws dynamodb delete-item --region "$REGION" --table-name "$PLAYERS" --key "{\"uuid\":{\"S\":\"$u\"}}" >/dev/null 2>&1
    aws dynamodb delete-item --region "$REGION" --table-name "$QUEUE" --key "{\"uuid\":{\"S\":\"$u\"}}" >/dev/null 2>&1
  done
  for t in "$A_TOK" "$B_TOK"; do
    aws dynamodb delete-item --region "$REGION" --table-name "$SESSIONS" --key "{\"token\":{\"S\":\"$t\"}}" >/dev/null 2>&1
  done
  [ -n "${MATCH_ID:-}" ] && aws dynamodb delete-item --region "$REGION" --table-name "$MATCHES" --key "{\"matchId\":{\"S\":\"$MATCH_ID\"}}" >/dev/null 2>&1
}
trap cleanup EXIT

now=$(date +%s); now_ms=$((now*1000)); exp=$((now+3600))
for pair in "$A_UUID:$A_TOK:Alice" "$B_UUID:$B_TOK:Bob"; do
  u="${pair%%:*}"; rest="${pair#*:}"; t="${rest%%:*}"; n="${rest##*:}"
  aws dynamodb put-item --region "$REGION" --table-name "$PLAYERS" --item \
    "{\"uuid\":{\"S\":\"$u\"},\"username\":{\"S\":\"$n\"},\"skillRating\":{\"N\":\"1500\"},\"seasonPoints\":{\"N\":\"0\"},\"createdAt\":{\"N\":\"$now_ms\"},\"lastLoginAt\":{\"N\":\"$now_ms\"}}" >/dev/null
  aws dynamodb put-item --region "$REGION" --table-name "$SESSIONS" --item \
    "{\"token\":{\"S\":\"$t\"},\"uuid\":{\"S\":\"$u\"},\"createdAt\":{\"N\":\"$now_ms\"},\"expiresAt\":{\"N\":\"$exp\"}}" >/dev/null
done

join() { curl -s -X POST "$API/queue/join" -H "Authorization: Bearer $1"; }
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('$1',''))" <<<"$2" 2>/dev/null; }

echo "Two-player matchmaking handshake"

# 1. Alice queues alone.
RA=$(join "$A_TOK")
check "Alice alone is not matched" "$(field matched "$RA")" "False"

# 2. Bob polls, finds Alice, creates the match. Only Bob sees this.
RB=$(join "$B_TOK")
check "Bob is matched" "$(field matched "$RB")" "True"
MATCH_ID=$(field matchId "$RB")
B_SEED=$(field overworldSeed "$RB")
check "Bob's opponent is Alice" "$(python3 -c "import json,sys;print(json.load(sys.stdin)['opponent']['username'])" <<<"$RB")" "Alice"

# 3. The fix: Alice's next poll must surface the match Bob created.
RA2=$(join "$A_TOK")
check "Alice discovers the match Bob created" "$(field matched "$RA2")" "True"
check "Alice gets the same matchId" "$(field matchId "$RA2")" "$MATCH_ID"
check "Alice gets the same overworld seed" "$(field overworldSeed "$RA2")" "$B_SEED"
check "Alice's opponent is Bob" "$(python3 -c "import json,sys;print(json.load(sys.stdin)['opponent']['username'])" <<<"$RA2")" "Bob"

# 4. Neither player should be left sitting in the queue.
QCOUNT=$(aws dynamodb scan --region "$REGION" --table-name "$QUEUE" \
  --filter-expression "begins_with(#u, :p)" \
  --expression-attribute-names '{"#u":"uuid"}' \
  --expression-attribute-values '{":p":{"S":"tpt-"}}' \
  --select COUNT --query Count --output text)
check "queue is empty for both players" "$QCOUNT" "0"

# 5. Completion must clear the pointer, or a finished match would be
#    handed back on the next poll.
curl -s -X POST "$API/matches/complete" -H "Authorization: Bearer $B_TOK" \
  -H "Content-Type: application/json" \
  -d "{\"matchId\":\"$MATCH_ID\",\"winnerUuid\":\"$B_UUID\"}" >/dev/null
PTR=$(aws dynamodb get-item --region "$REGION" --table-name "$PLAYERS" \
  --key "{\"uuid\":{\"S\":\"$A_UUID\"}}" --query 'Item.currentMatchId.S' --output text)
check "currentMatchId cleared after completion" "$PTR" "None"

RA3=$(join "$A_TOK")
check "Alice is not re-handed the finished match" "$(field matched "$RA3")" "False"

echo ""
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
