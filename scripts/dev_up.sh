#!/usr/bin/env bash
# chat-app을 도커로 빌드·기동한다 (board 스택에 붙는 로컬 실행).
#   scripts/dev_up.sh            빌드 후 기동, healthy 될 때까지 대기
#   scripts/dev_up.sh --no-build 이미지 재빌드 없이 기동
# 전제: board compose가 떠 있어 mysql-8, board-redis 가 board-db-net 에 있고, .env 가 채워져 있다.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "✖ .env 가 없습니다. cp .env.example .env 후 JWT_SECRET, DB_PASSWORD 를 채우세요." >&2
  exit 1
fi
set -a; source .env; set +a
: "${JWT_SECRET:?.env 의 JWT_SECRET 이 비어 있습니다}"
: "${DB_PASSWORD:?.env 의 DB_PASSWORD 가 비어 있습니다}"

echo "▶ board 인프라 확인"
docker network inspect board-db-net >/dev/null 2>&1 || { echo "✖ 네트워크 board-db-net 없음 — board compose를 먼저 띄우세요" >&2; exit 1; }
for c in mysql-8 board-redis; do
  docker ps --format '{{.Names}}' | grep -qx "$c" || { echo "✖ 컨테이너 $c 가 실행 중이 아닙니다" >&2; exit 1; }
done

echo "▶ chat DB 준비"
scripts/init_db.sh

BUILD="--build"
[[ "${1:-}" == "--no-build" ]] && BUILD="--no-build"
echo "▶ 기동 ($BUILD) — healthy 까지 대기"
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --wait --remove-orphans $BUILD

echo "▶ 상태"
docker compose -f docker-compose.yml -f docker-compose.local.yml ps
curl -s http://localhost:8092/actuator/health; echo
echo
echo "콘솔:  http://localhost:8092/index.html"
echo "검증:  scripts/smoke_test.sh"
echo "로그:  docker compose -f docker-compose.yml -f docker-compose.local.yml logs -f chat-app"
echo "종료:  scripts/dev_down.sh"
