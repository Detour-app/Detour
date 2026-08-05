#!/usr/bin/env bash
# Verifies a Detour server install against the success criteria.
#
#   ./verify.sh                 # check the sync server on localhost:8790
#   ./verify.sh --routing       # also check GraphHopper on localhost:8989
#   INVITE=xyz ./verify.sh      # if registration is gated by an invite code
#
# Creates three throwaway accounts (verify_*) and deletes them at the end if it
# can reach the database. Safe to run against a live server.
set -u

SYNC="${SYNC_URL:-http://localhost:8790}"
GH="${GH_URL:-http://localhost:8989}"
DB="${DB:-/var/lib/detour-sync/detour.db}"
INVITE="${INVITE:-}"
CHECK_ROUTING=0
[ "${1:-}" = "--routing" ] && CHECK_ROUTING=1

fails=0
ck() {
  if [ "$2" = "$3" ]; then echo "PASS  $1"
  else echo "FAIL  $1 (got=$2 want=$3)"; fails=$((fails + 1)); fi
}
jq_() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" 2>/dev/null || echo "<parse-error>"; }

A="verify_a_$RANDOM"; B="verify_b_$RANDOM"; C="verify_c_$RANDOM"
PW="verify-password-123"

echo "== sync server ($SYNC)"
ck "health"          "$(curl -s $SYNC/health)" "ok"
ck "sync needs auth" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $SYNC/sync -d '{}')" "401"
# The dashboard shell is public; its data is not. A 200 on the second line here
# would mean anyone reaching the hostname can list every account.
ck "admin page serves"  "$(curl -s -o /dev/null -w '%{http_code}' $SYNC/admin)" "200"
ck "admin data private" "$(curl -s -o /dev/null -w '%{http_code}' $SYNC/admin/api/overview)" "401"

# Registration body carries the invite code only when one is configured. A wrong
# invite counts as a failed auth attempt, so never guess here.
reg() {
  python3 -c "import json,os,sys; b={'username':sys.argv[1],'password':sys.argv[2]}; \
inv=os.environ.get('INVITE') or ''; b.update({'invite':inv} if inv else {}); print(json.dumps(b))" "$1" "$PW" \
    | curl -s -X POST $SYNC/auth/register --data-binary @- | jq_ 'd.get("token","")'
}
TA=$(reg "$A"); TB=$(reg "$B"); TC=$(reg "$C")
if [ -z "$TA" ] || [ -z "$TB" ] || [ -z "$TC" ]; then
  echo "FAIL  registration — is REGISTRATION_OPEN=0, or is INVITE unset while INVITE_CODE is configured?"
  exit 1
fi

BODY='{"trips":[{"startTimeMs":1,"distanceMeters":1000.0,"topSpeedMps":30.0}],
       "traces":["[[52.0,5.0],[52.1,5.1]]"],"badges":{"dist_100000":1700000000000},
       "stats":{"totalDistanceMeters":1000.0,"topSpeedKmh":108.0,"tripCount":1}}'
ck "A syncs"           "$(curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TA" -d "$BODY" | jq_ 'len(d["trips"])')" "1"
ck "idempotent trips"  "$(curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TA" -d "$BODY" | jq_ 'len(d["trips"])')" "1"
ck "idempotent traces" "$(curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TA" -d "$BODY" | jq_ 'len(d["traces"])')" "1"
ck "idempotent badges" "$(curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TA" -d "$BODY" | jq_ 'len(d["badges"])')" "1"
ck "B sees no A trips"  "$(curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TB" -d '{"trips":[],"traces":[]}' | jq_ 'len(d["trips"])')" "0"
ck "B sees no A traces" "$(curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TB" -d '{"trips":[],"traces":[]}' | jq_ 'len(d["traces"])')" "0"

echo "== friends and the privacy guarantee"
curl -s -X POST $SYNC/friends/request -H "Authorization: Bearer $TA" -d "{\"username\":\"$B\"}" >/dev/null
ck "no stats while pending" "$(curl -s $SYNC/friends/stats -H "Authorization: Bearer $TB" | jq_ 'len(d)')" "0"
curl -s -X POST $SYNC/friends/respond -H "Authorization: Bearer $TB" -d "{\"username\":\"$A\",\"accept\":true}" >/dev/null
ck "friend visible"     "$(curl -s $SYNC/friends/stats -H "Authorization: Bearer $TB" | jq_ 'len(d)')" "1"
ck "friend km shared"   "$(curl -s $SYNC/friends/stats -H "Authorization: Bearer $TB" | jq_ 'int(d[0]["stats"]["totalDistanceMeters"])')" "1000"
ck "NO trips leaked"    "$(curl -s $SYNC/friends/stats -H "Authorization: Bearer $TB" | jq_ '"trips" in d[0]')" "False"
ck "NO traces leaked"   "$(curl -s $SYNC/friends/stats -H "Authorization: Bearer $TB" | jq_ '"traces" in d[0]')" "False"

echo "== shared fog (off by default, reciprocal, revocable)"
ck "fog off by default"      "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'd["sharing"]')" "False"
ck "fog off returns none"    "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'len(d["traces"])')" "0"

# B opts in and has its own trace; A has not opted in, so A must still see nothing.
curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TB" \
  -d '{"trips":[],"traces":["[[51.0,4.0],[51.1,4.1]]"],"shareFog":true}' >/dev/null
ck "non-sharer gets nothing" "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'len(d["traces"])')" "0"

curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TA" -d '{"trips":[],"traces":[],"shareFog":true}' >/dev/null
ck "sharing flag true"       "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'd["sharing"]')" "True"
ck "sees sharing friend fog" "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'len(d["traces"])')" "1"
ck "fog has NO trips key"    "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ '"trips" in d')" "False"

# An absent key must mean "leave it alone", not "clear it".
curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TA" -d '{"trips":[],"traces":[]}' >/dev/null
ck "absent shareFog kept"    "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'd["sharing"]')" "True"
ck "absent stats kept"       "$(curl -s $SYNC/me -H "Authorization: Bearer $TA" | jq_ 'int(d["stats"]["totalDistanceMeters"])')" "1000"

# A pending friend who shares fog must not contribute any.
curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TC" \
  -d '{"trips":[],"traces":["[[50.0,3.0],[50.1,3.1]]"],"shareFog":true}' >/dev/null
curl -s -X POST $SYNC/friends/request -H "Authorization: Bearer $TC" -d "{\"username\":\"$A\"}" >/dev/null
ck "pending friend no fog"   "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'len(d["traces"])')" "1"

curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TB" -d '{"trips":[],"traces":[],"shareFog":false}' >/dev/null
ck "revoke hides friend fog" "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'len(d["traces"])')" "0"
curl -s -X POST $SYNC/sync -H "Authorization: Bearer $TA" -d '{"trips":[],"traces":[],"shareFog":false}' >/dev/null
ck "self revoke stops recv"  "$(curl -s $SYNC/friends/fog -H "Authorization: Bearer $TA" | jq_ 'd["sharing"]')" "False"

echo "== brute-force lockout"
# Must run last: the failure budget is per-IP, and a lockout would break the
# checks above. Successful logins never consume it.
ck "good login ok" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $SYNC/auth/login -d "{\"username\":\"$A\",\"password\":\"$PW\"}")" "200"
for _ in $(seq 1 11); do
  LAST=$(curl -s -o /dev/null -w '%{http_code}' -X POST $SYNC/auth/login -d "{\"username\":\"$A\",\"password\":\"wrongwrong\"}")
done
ck "brute force locked out" "$LAST" "429"

if [ "$CHECK_ROUTING" = "1" ]; then
  echo "== routing server ($GH)"
  ck "three profiles" "$(curl -s $GH/info | jq_ 'len(d["profiles"])')" "3"
  for p in moto car bike; do
    ck "profile $p" "$(curl -s $GH/info | jq_ "'$p' in [x['name'] for x in d['profiles']]")" "True"
  done
  # A round trip is what the app's Moto spin actually issues.
  PT="${GH_POINT:-50.85,4.35}"
  ck "moto round trip" \
    "$(curl -s "$GH/route?profile=moto&point=$PT&algorithm=round_trip&round_trip.distance=50000&round_trip.seed=42&points_encoded=false" | jq_ '"paths" in d')" "True"
fi

echo
if [ -f "$DB" ] && [ -w "$DB" ]; then
  python3 -c "
import sqlite3
c = sqlite3.connect('$DB'); c.execute('PRAGMA foreign_keys=ON')
n = c.execute(\"DELETE FROM users WHERE username LIKE 'verify_%'\").rowcount
c.commit(); print('cleaned up %d test accounts' % n)"
else
  echo "NOTE: could not reach $DB to delete the verify_* test accounts; remove them manually."
fi

echo
[ $fails -eq 0 ] && echo "ALL PASS" || echo "$fails FAILURES"
exit $fails
