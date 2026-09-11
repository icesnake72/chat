#!/usr/bin/env bash
# chat 데이터베이스를 mysql-8 컨테이너에 만든다 (없을 때만). board DB는 건드리지 않는다.
set -euo pipefail

: "${DB_USERNAME:=root}"
: "${DB_PASSWORD:?DB_PASSWORD 환경변수가 필요합니다 (.env 참고)}"
: "${DB_NAME:=chat}"

docker exec mysql-8 mysql -u"${DB_USERNAME}" -p"${DB_PASSWORD}" \
  -e "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "database '${DB_NAME}' ready"
