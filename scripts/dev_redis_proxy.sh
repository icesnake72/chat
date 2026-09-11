#!/usr/bin/env bash
# board-redis는 호스트 포트를 열지 않는다. bootRun(호스트 JVM)에서 denylist를 읽으려면
# board-db-net 안의 socat 컨테이너로 127.0.0.1:6380 → board-redis:6379 터널을 연다.
#   start: scripts/dev_redis_proxy.sh
#   stop:  scripts/dev_redis_proxy.sh stop
set -euo pipefail

NAME=chat-dev-redis-proxy
if [[ "${1:-start}" == "stop" ]]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  echo "stopped $NAME"
  exit 0
fi

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --network board-db-net -p 127.0.0.1:6380:6379 \
  alpine/socat TCP-LISTEN:6379,fork,reuseaddr TCP:board-redis:6379 >/dev/null
echo "127.0.0.1:6380 -> board-redis:6379 (container $NAME)"
