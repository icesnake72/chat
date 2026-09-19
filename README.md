# chat

board(`../board`) 인증을 재사용하는 STOMP 채팅 서버. 설계: `docs/design/2026-09-12-stomp-chat-design.md`, 따라하기: `docs/lecture/day1_auth_stomp_walkthrough.md`, `day2_rooms_messages_walkthrough.md`. 채팅 UI는 board 프론트엔드에 통합한다(`docs/design/frontend_integration_scope.md`).

## 도커로 실행

전제: board compose가 떠 있어 `mysql-8`, `board-redis`, `board-app`이 `board-db-net`에 있다.

```bash
cp .env.example .env      # JWT_SECRET(board와 동일 값), DB_PASSWORD 채우기 (1회)
scripts/dev_up.sh         # chat DB 생성 → 이미지 빌드 → 기동 → healthy 대기
scripts/smoke_test.sh     # REST + STOMP 스모크 테스트 (19개 검사)
scripts/dev_down.sh       # chat-app만 종료 (board 스택·데이터는 유지)
```

| 스크립트 | 하는 일 |
|---|---|
| `scripts/dev_up.sh [--no-build]` | board 인프라 확인 → `scripts/init_db.sh` → `docker compose ... up -d --wait` → health 출력 |
| `scripts/smoke_test.sh [username]` | `.env`의 `JWT_SECRET`으로 토큰을 직접 만들어(`scripts/mkjwt.py`) health, 인증 401/200, 방 생성·중복·검증·입장·멤버, STOMP 왕복(`scripts/stomp_probe.mjs`)·에러 큐·인증 거부 2종, 이력, 삭제까지 검사. board 로그인 없이 동작한다 |
| `scripts/stomp_probe.mjs <token> <roomId> [content] [--expect-error CODE]` | 원시 STOMP 프레임으로 CONNECT → SUBSCRIBE → SEND (Node 22+, 라이브러리 없음) |
| `scripts/dev_redis_proxy.sh` | `./gradlew bootRun`(호스트 JVM)일 때 `127.0.0.1:6379 → board-redis` 터널 |

브라우저 콘솔: `http://localhost:8092/index.html` (토큰 붙여넣기 → CONNECT → 방 생성·입장·전송).

board 로그인 토큰으로 테스트하려면(`.env`의 `JWT_SECRET`이 board와 같을 때):

```bash
curl -s -X POST http://localhost/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin1234"}'
```

## board compose에 합쳐서 실행 (통합 개발)

board `docker-compose.yml`에 한 줄을 넣으면 `~/board`에서 `docker compose up` 한 번으로 board와 chat이 같은 프로젝트로 뜬다.

```yaml
include:
  - ../chat/docker-compose.yml
```

호스트에서 `localhost:8092`로 직접 붙어야 하면(스모크 테스트·콘솔) board에 `docker-compose.override.yml`(gitignore, 로컬 전용)을 둔다.

```yaml
services:
  chat-app:
    ports:
      - "127.0.0.1:8092:8092"
```

주의: chat 단독 실행(`scripts/dev_up.sh`)과 board `include`를 동시에 쓰면 컨테이너명 `chat-app`이 충돌한다. 한쪽만 쓴다. board nginx가 `/api/v1/chat/`·`/ws`를 프록시하면 포트 공개 없이 `CHAT_BASE_URL=http://localhost CHAT_WS_URL=ws://localhost/ws scripts/smoke_test.sh`로도 검사할 수 있다.

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `JWT_SECRET` | 없음(필수) | board와 동일한 Base64 secret |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | `localhost`, `3306`, `chat`, `root`, 없음(필수) | compose가 `DB_HOST=mysql-8`로 덮어쓴다 |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | compose가 `REDIS_HOST=board-redis`로 덮어쓴다. chat은 `deny:{jti}`를 읽기만 한다 |
| `APP_WS_ALLOWED_ORIGINS` | `http://localhost,http://localhost:*` | WebSocket 핸드셰이크 Origin 허용 패턴(쉼표 구분). 브라우저는 80/443 포트를 Origin에서 생략하므로 포트 없는 형태가 필요하다. 운영: `https://sbs.alldayai.org` |
| `APP_BOARD_SCHEMA` | `board` | 사용자 조회에 쓰는 board 스키마 이름 |

## 호스트에서 실행 (bootRun)

```bash
scripts/dev_redis_proxy.sh && ./gradlew bootRun    # .env: REDIS_HOST=localhost, REDIS_PORT=6379
```

## 단위·통합 테스트

```bash
./gradlew test        # H2 + InMemory denylist, 87개
```
