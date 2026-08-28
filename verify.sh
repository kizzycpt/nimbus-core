#!/usr/bin/env bash
#
# Nimbus Core — end-to-end verification.
#
# Exercises every endpoint through nginx exactly as a browser would: CSRF
# bootstrap, registration, session cookies, tickets, website hosting (including
# path-traversal refusals), account settings and teardown. Creates a throwaway
# user and site and removes both, so it is safe to run against a live instance.
#
#   ./verify.sh                      test http://127.0.0.1:8080
#   ./verify.sh --wait               sit out the rate limiter instead of failing
#   BASE_URL=https://x ./verify.sh   test somewhere else
#
# Note: nginx rate-limits /api/register to 5/min and /api/login to 10/min, so
# running this repeatedly in quick succession trips the limiter. That is the
# limiter working, not a bug — use --wait.
#
set -u
BASE="${BASE_URL:-http://127.0.0.1:8080}/api"
TMP=$(mktemp -d)
JAR="$TMP/cookies"
USER="e2e_$RANDOM"
PASS="correct-horse-battery"
PASS2="staple-battery-horse-9"
pass=0; fail=0
WAIT_FOR_LIMITER=0
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  sed -n '2,15p' "$0" | sed 's/^#//'
  exit 0
fi
[[ "${1:-}" == "--wait" ]] && WAIT_FOR_LIMITER=1

c_ylw() { printf '\033[33m%s\033[0m\n' "$*"; }

ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1  -- $2"; fail=$((fail+1)); }
check(){ [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1" "expected $3 got $2"; }

csrf() { grep -i 'XSRF-TOKEN' "$JAR" | awk '{print $7}' | tail -1; }

# nginx throttles /api/login and /api/register to 5/min. Hitting that is the
# limiter working, not a broken API, so wait it out rather than reporting a
# cascade of confusing failures.
wait_out_limiter() {
  local endpoint="$1"
  c_ylw "  nginx rate limit hit on $endpoint (429)."
  if (( WAIT_FOR_LIMITER )); then
    echo "  waiting 65s for the window to clear..."
    sleep 65
    return 0
  fi
  echo
  echo "  Registration allows 5 requests/minute per IP. Either wait a minute"
  echo "  and re-run, or use:  ./verify.sh --wait"
  exit 2
}

# ---- public endpoints -------------------------------------------------
code=$(curl -s -o "$TMP/o" -w '%{http_code}' "$BASE/health"); check "GET /health" "$code" 200
code=$(curl -s -o "$TMP/s" -w '%{http_code}' "$BASE/status"); check "GET /status" "$code" 200
echo "        status body: $(cat "$TMP/s")"

# ---- csrf bootstrap ---------------------------------------------------
curl -s -c "$JAR" -b "$JAR" "$BASE/csrf" > /dev/null
T=$(csrf); [ -n "$T" ] && ok "CSRF cookie issued" || bad "CSRF cookie issued" "empty"

# ---- CSRF enforcement -------------------------------------------------
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/register" \
  -H 'Content-Type: application/json' -d "{\"username\":\"nope$RANDOM\",\"password\":\"$PASS\"}")
[ "$code" = "429" ] && { wait_out_limiter "/register"; code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/register" \
  -H 'Content-Type: application/json' -d "{\"username\":\"nope$RANDOM\",\"password\":\"$PASS\"}"); }
check "POST /register without CSRF is rejected" "$code" 403

# ---- register ---------------------------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o "$TMP/o" -w '%{http_code}' -X POST "$BASE/register" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
[ "$code" = "429" ] && { wait_out_limiter "/register"; code=$(curl -s -c "$JAR" -b "$JAR" -o "$TMP/o" -w '%{http_code}' -X POST "$BASE/register" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}"); }
check "POST /register" "$code" 201

grep -q 'nimbus_session' "$JAR" && ok "session cookie set on register" || bad "session cookie" "absent"
grep 'nimbus_session' "$JAR" | grep -q '^#HttpOnly_' && ok "session cookie is HttpOnly" || bad "HttpOnly flag" "missing"

# ---- weak password rejected ------------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o "$TMP/o" -w '%{http_code}' -X POST "$BASE/register" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"username\":\"weak$RANDOM\",\"password\":\"short\"}")
check "weak password rejected" "$code" 400

# ---- authenticated reads ---------------------------------------------
code=$(curl -s -b "$JAR" -o "$TMP/me" -w '%{http_code}' "$BASE/me"); check "GET /me" "$code" 200
echo "        me: $(cat "$TMP/me")"
code=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/protected"); check "GET /protected with cookie" "$code" 200
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/protected"); check "GET /protected without cookie" "$code" 401

# ---- tickets ----------------------------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o "$TMP/t" -w '%{http_code}' -X POST "$BASE/tickets" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"subject":"Disk is full","body":"Need more storage on my box.","kind":"CHANGE"}')
check "POST /tickets" "$code" 201
TID=$(python3 -c "import json;print(json.load(open('"$TMP/t"'))['id'])" 2>/dev/null)
[ -n "$TID" ] || bad "ticket id extraction" "could not parse id from the create response"
echo "        ticket id: $TID"

code=$(curl -s -b "$JAR" -o "$TMP/tl" -w '%{http_code}' "$BASE/tickets"); check "GET /tickets" "$code" 200

code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/tickets/$TID/comments" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"body":"Any update on this?"}')
check "POST ticket comment" "$code" 201

code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X PATCH "$BASE/tickets/$TID" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"status":"IN_PROGRESS"}')
check "PATCH ticket status" "$code" 200

code=$(curl -s -b "$JAR" -o "$TMP/td" -w '%{http_code}' "$BASE/tickets/$TID"); check "GET ticket detail" "$code" 200
python3 -c "
import json;d=json.load(open('"$TMP/td"'))
print('        detail: status=%s kind=%s comments=%d' % (d['status'], d['kind'], len(d['comments'])))"

code=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/tickets/999999"); check "other/missing ticket is 404" "$code" 404

# ---- website hosting ---------------------------------------------------
SLUG="vsite$RANDOM"
code=$(curl -s -c "$JAR" -b "$JAR" -o "$TMP/site" -w '%{http_code}' -X POST "$BASE/sites" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"slug\":\"$SLUG\"}")
check "POST /sites" "$code" 201

code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/sites" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"slug\":\"$SLUG\"}")
check "duplicate slug rejected" "$code" 409

code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/sites" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" -d '{"slug":"api"}')
check "reserved slug rejected" "$code" 400

echo "hello from $SLUG" > "$TMP/index.html"
code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/sites/$SLUG/files" \
  -H "X-XSRF-TOKEN: $(csrf)" -F "file=@$TMP/index.html" --form-string "path=index.html")
check "upload a file" "$code" 201

code=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/sites/$SLUG/files")
check "list site files" "$code" 200

# Path traversal: every one of these must be refused.
for evil in "../../../etc/passwd" "/etc/passwd" "ok/../../escape.txt" ".ssh/authorized_keys"; do
  code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/sites/$SLUG/files" \
    -H "X-XSRF-TOKEN: $(csrf)" -F "file=@$TMP/index.html" --form-string "path=$evil")
  check "traversal refused: $evil" "$code" 400
done

# Routing can only be asserted once the site's container exists, and that is
# the reconciler's job. Drive it here so the test covers the whole path instead
# of stopping at the API boundary.
if [ -x ./site-reconciler.sh ] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ./site-reconciler.sh >/dev/null 2>&1 || true
fi

if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "nimbus-site-$SLUG"; then
  # A container that has just been created takes a moment to register in
  # Docker's DNS, and nginx resolves the upstream at request time. Poll rather
  # than single-shot: the product promises the site is live within a reconcile
  # interval, not within a millisecond.
  code=000
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    code=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL:-http://127.0.0.1:8080}/s/$SLUG/")
    [ "$code" = "200" ] && break
    sleep 1
  done
  check "site is served" "$code" 200
  csp=$(curl -s -D - -o /dev/null "${BASE_URL:-http://127.0.0.1:8080}/s/$SLUG/" | grep -i '^content-security-policy' | head -1)
  case "$csp" in
    *sandbox*) ok "customer content is sandboxed (opaque origin)" ;;
    *)         bad "customer content sandbox" "CSP was: $csp" ;;
  esac
else
  c_ylw "  SKIP  site routing — run ./site-reconciler.sh to build the container"
fi

code=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL:-http://127.0.0.1:8080}/s/nosuchsite12345/")
check "unpublished slug is 404" "$code" 404

# ---- sessions ---------------------------------------------------------
code=$(curl -s -b "$JAR" -o "$TMP/s"s -w '%{http_code}' "$BASE/me/sessions"); check "GET /me/sessions" "$code" 200
python3 -c "
import json;d=json.load(open('"$TMP/s"s'))
print('        sessions: %d (current flagged: %s)' % (len(d), any(s['current'] for s in d)))"

# ---- api token --------------------------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o "$TMP/tk" -w '%{http_code}' -X POST "$BASE/me/api-token" \
  -H "X-XSRF-TOKEN: $(csrf)")
check "POST /me/api-token" "$code" 200
TOKEN=$(python3 -c "import json;print(json.load(open('"$TMP/tk"'))['token'])" 2>/dev/null)
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/protected" -H "Authorization: Bearer $TOKEN")
check "bearer token authenticates (no cookie, no CSRF)" "$code" 200

# ---- password change --------------------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o "$TMP/o" -w '%{http_code}' -X POST "$BASE/me/password" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"currentPassword\":\"wrong-password\",\"newPassword\":\"$PASS2\"}")
check "password change with wrong current password" "$code" 403

code=$(curl -s -c "$JAR" -b "$JAR" -o "$TMP/o" -w '%{http_code}' -X POST "$BASE/me/password" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"currentPassword\":\"$PASS\",\"newPassword\":\"$PASS2\"}")
check "password change" "$code" 200
code=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/me"); check "own session survives password change" "$code" 200

# ---- logout -----------------------------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/logout" \
  -H "X-XSRF-TOKEN: $(csrf)")
check "POST /logout" "$code" 200
code=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/me"); check "session dead after logout" "$code" 401

# ---- login with the new password --------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/login" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS2\"}")
check "login with new password" "$code" 200
code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/login" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
check "old password rejected" "$code" 401

# ---- site teardown ----------------------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X DELETE "$BASE/sites/$SLUG" \
  -H "X-XSRF-TOKEN: $(csrf)")
check "DELETE /sites/$SLUG" "$code" 200

# Removing the site removes its container on the next pass — assert that too,
# otherwise a leaked container would go unnoticed until the host ran out of RAM.
if [ -x ./site-reconciler.sh ] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ./site-reconciler.sh >/dev/null 2>&1 || true
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "nimbus-site-$SLUG"; then
    bad "site container removed" "nimbus-site-$SLUG is still present"
  else
    ok "site container removed by the reconciler"
  fi
fi

# ---- account deletion -------------------------------------------------
code=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE/me/delete" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d "{\"password\":\"$PASS2\"}")
check "POST /me/delete" "$code" 200
code=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/me"); check "account gone" "$code" 401

# ---- actuator must not be reachable through nginx ----------------------
code=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL:-http://127.0.0.1:8080}/api/actuator/health")
check "actuator blocked at the edge" "$code" 404

# ---- static pages ------------------------------------------------------
for p in / /login.html /register.html /dashboard.html /account.html /tickets.html /status.html; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL:-http://127.0.0.1:8080}$p")
  check "GET $p" "$code" 200
done

rm -rf "$TMP"
echo
echo "  ---- $pass passed, $fail failed ----"
exit $(( fail > 0 ))
