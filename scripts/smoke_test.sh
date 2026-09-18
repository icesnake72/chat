#!/usr/bin/env bash
# 기동 중인 chat-app(기본 http://localhost:8092)에 대해 REST + STOMP 스모크 테스트.
# .env 의 JWT_SECRET 으로 토큰을 직접 만들어 쓴다 — board 로그인 없이 검증 가능
# (JWT_SECRET 이 board 와 같으면 board 토큰과 동일하게 취급된다).
#   scripts/smoke_test.sh            사용자 admin 으로
#   scripts/smoke_test.sh <username> board.users 에 있는 다른 사용자로
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
: "${JWT_SECRET:?.env 의 JWT_SECRET 이 필요합니다}"

BASE="${CHAT_BASE_URL:-http://localhost:8092}"
USER_NAME="${1:-admin}"
PASS=0; FAIL=0
ok()   { echo "  ✔ $1"; PASS=$((PASS+1)); }
fail() { echo "  ✖ $1"; FAIL=$((FAIL+1)); }
check() { # check <설명> <기대 HTTP> <curl 인자...>
  local desc="$1" expect="$2"; shift 2
  local code; code=$(curl -s -m 5 -o /tmp/smoke_body -w '%{http_code}' "$@")
  if [[ "$code" == "$expect" ]]; then ok "$desc ($code)"; else fail "$desc — expected $expect got $code: $(head -c 200 /tmp/smoke_body)"; fi
}

echo "▶ 1. health"
check "GET /actuator/health" 200 "$BASE/actuator/health"
grep -q '"status":"UP"' /tmp/smoke_body && ok "status UP (MySQL·Redis 연결)" || fail "status not UP: $(cat /tmp/smoke_body)"

echo "▶ 2. 인증"
TOKEN=$(JWT_SECRET="$JWT_SECRET" python3 scripts/mkjwt.py "$USER_NAME")
EXPIRED=$(JWT_SECRET="$JWT_SECRET" python3 scripts/mkjwt.py "$USER_NAME" -60)
AUTH=(-H "Authorization: Bearer $TOKEN")
check "GET /me 토큰 없음 → 401" 401 "$BASE/api/v1/chat/me"
check "GET /me 만료 토큰 → 401" 401 -H "Authorization: Bearer $EXPIRED" "$BASE/api/v1/chat/me"
check "GET /me → 200" 200 "${AUTH[@]}" "$BASE/api/v1/chat/me"
ME=$(cat /tmp/smoke_body); echo "    me=$ME"
grep -q "\"username\":\"$USER_NAME\"" /tmp/smoke_body || fail "username 불일치 — board.users 에 '$USER_NAME' 이 있고 JWT_SECRET 이 맞는지 확인"

echo "▶ 3. 방"
NAME="smoke-$(date +%s)"
check "POST /rooms → 201" 201 "${AUTH[@]}" -H 'Content-Type: application/json' -d "{\"name\":\"$NAME\",\"description\":\"smoke\"}" "$BASE/api/v1/chat/rooms"
ROOM_ID=$(python3 -c 'import json,sys;print(json.load(open("/tmp/smoke_body"))["id"])')
check "POST /rooms 같은 이름 → 409" 409 "${AUTH[@]}" -H 'Content-Type: application/json' -d "{\"name\":\"$NAME\"}" "$BASE/api/v1/chat/rooms"
check "POST /rooms 빈 이름 → 400" 400 "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"name":"  "}' "$BASE/api/v1/chat/rooms"
check "GET /rooms → 200" 200 "${AUTH[@]}" "$BASE/api/v1/chat/rooms?size=5"
check "POST /rooms/$ROOM_ID/join (멱등) → 200" 200 -X POST "${AUTH[@]}" "$BASE/api/v1/chat/rooms/$ROOM_ID/join"
check "GET /rooms/$ROOM_ID/members → 200" 200 "${AUTH[@]}" "$BASE/api/v1/chat/rooms/$ROOM_ID/members"

echo "▶ 4. STOMP"
if node scripts/stomp_probe.mjs "$TOKEN" "$ROOM_ID" "hello from smoke" | sed 's/^/    /'; then ok "CONNECT → SUBSCRIBE → SEND → TALK 수신"; else fail "STOMP 왕복 실패"; fi
if node scripts/stomp_probe.mjs "$TOKEN" "$ROOM_ID" "   " --expect-error INVALID_INPUT | sed 's/^/    /'; then ok "빈 메시지 → /user/queue/errors INVALID_INPUT"; else fail "빈 메시지 처리"; fi
if node scripts/stomp_probe.mjs "" "$ROOM_ID" --expect-error LOGIN_REQUIRED | sed 's/^/    /'; then ok "토큰 없이 CONNECT → ERROR LOGIN_REQUIRED"; else fail "무토큰 CONNECT 거부"; fi
if node scripts/stomp_probe.mjs "$EXPIRED" "$ROOM_ID" --expect-error TOKEN_EXPIRED | sed 's/^/    /'; then ok "만료 토큰 CONNECT → ERROR TOKEN_EXPIRED"; else fail "만료 CONNECT 거부"; fi

echo "▶ 5. 이력"
check "GET /rooms/$ROOM_ID/messages → 200" 200 "${AUTH[@]}" "$BASE/api/v1/chat/rooms/$ROOM_ID/messages?size=10"
grep -q '"type":"TALK"' /tmp/smoke_body && grep -q '"type":"ENTER"' /tmp/smoke_body && ok "ENTER·TALK 저장 확인" || fail "이력에 ENTER/TALK 없음: $(head -c 300 /tmp/smoke_body)"

echo "▶ 6. 정리"
check "DELETE /rooms/$ROOM_ID (소유자) → 204" 204 -X DELETE "${AUTH[@]}" "$BASE/api/v1/chat/rooms/$ROOM_ID"
check "GET /rooms/$ROOM_ID → 404" 404 "${AUTH[@]}" "$BASE/api/v1/chat/rooms/$ROOM_ID"

echo
echo "결과: PASS=$PASS FAIL=$FAIL"
[[ $FAIL -eq 0 ]]
