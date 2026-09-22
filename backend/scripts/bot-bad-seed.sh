#!/bin/bash
# Makes PaceBot vote that the current match's seed is unplayable.
#
#   bash scripts/bot-bad-seed.sh [matchId]
#
# Exists to test the SECOND half of the bad-seed vote. One player
# voting is easy to exercise - click the button - and correctly does
# nothing. The half that actually ends a match needs two players to
# agree, and PaceBot has no opinions, so it needs telling.
#
# Reuses the session pace-bot.sh writes, so run this while a bot match
# is live. With no matchId it finds the pending match the bot is in.
#
# This is a TEST tool. Nothing in the real product votes on a player's
# behalf - that would defeat the entire point of requiring agreement
# between two opponents.
set -u

API="${API:-https://4q2boikc88.execute-api.us-west-2.amazonaws.com}"
REGION="${REGION:-us-west-2}"
MATCHES="${MATCHES_TABLE:-BackendStack-MatchesTable36E59E86-2WG7P01DU5BP}"
TOK="bot-tok-rival"

MATCH_ID="${1:-}"
if [ -z "$MATCH_ID" ]; then
  MATCH_ID=$(aws dynamodb scan --region "$REGION" --table-name "$MATCHES" \
    --filter-expression '#s = :p' \
    --expression-attribute-names '{"#s":"status"}' \
    --expression-attribute-values '{":p":{"S":"pending"}}' \
    --query 'Items[0].matchId.S' --output text)
fi

if [ -z "$MATCH_ID" ] || [ "$MATCH_ID" = "None" ]; then
  echo "No pending match found - is a match actually running?" >&2
  exit 1
fi

echo "PaceBot voting bad seed on $MATCH_ID"

# --globoff and printf for the body: building JSON inline inside a
# quoted string gets the escapes stripped, after which curl reads the
# body as a URL and brace-expands it on the commas.
BODY=$(printf '{"matchId":"%s","reason":"%s"}' "$MATCH_ID" "test vote from PaceBot")
RESP=$(curl -s --globoff -X POST "$API/matches/bad-seed" \
  -H "Authorization: Bearer $TOK" \
  -H 'content-type: application/json' \
  -d "$BODY")

echo "$RESP"
python3 - "$RESP" <<'PY'
import json, sys
try:
    r = json.loads(sys.argv[1])
except Exception:
    sys.exit(0)
if r.get('voided'):
    print('\n  -> BOTH agreed: match voided, no rating change, seed quarantined')
elif r.get('yourVote'):
    print('\n  -> bot vote recorded; waiting on the other player')
else:
    print('\n  -> unexpected response')
PY
