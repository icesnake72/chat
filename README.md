# chat

board(`../board`) 인증을 재사용하는 STOMP 채팅 서버. 설계: `docs/design/2026-09-12-stomp-chat-design.md`, 1일차 따라하기: `docs/lecture/day1_auth_stomp_walkthrough.md`.

## 로컬 실행

전제: board compose가 떠 있어 `mysql-8`, `board-redis`, `board-app`이 `board-db-net`에 있다.

1. `cp .env.example .env` 후 `JWT_SECRET`(board와 동일 값), `DB_PASSWORD`를 채운다.
2. `set -a; source .env; set +a; scripts/init_db.sh` — `chat` DB 생성 (1회)
3. 실행 방법 중 하나
   - 도커: `docker compose -f docker-compose.yml -f docker-compose.local.yml up --build`
   - bootRun: `scripts/dev_redis_proxy.sh && ./gradlew bootRun` (`.env`의 `REDIS_PORT=6379`)
4. `http://localhost:8092/index.html` 에서 board 토큰으로 CONNECT

board 토큰 얻기 (로컬 board는 caddy 경유 `http://localhost`):

```bash
curl -s -X POST http://localhost/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin1234"}'
```

## 테스트

```bash
./gradlew test
```
