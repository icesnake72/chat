#!/usr/bin/env bash
# chat-app 컨테이너만 내린다. board 스택(mysql-8, board-redis)과 chat DB 데이터는 그대로 둔다.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f docker-compose.yml -f docker-compose.local.yml down --remove-orphans
