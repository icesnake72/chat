# 서버 배포 체크리스트 (Lightsail, board 스택과 함께)

> 기준: 2026-09-20. board `docker-compose.yml`이 `include`로 `../chat/docker-compose.yml`을 끌어오고, board `scripts/deploy.sh`가 `~/chat`을 준비한다는 전제(4일차 계획 Task B). 명령은 `ec2-user`로 SSH 접속 후 실행.

배포 후 "chat-app이 떠 있고, nginx가 라우팅하고, 인증이 board와 맞물리는가"를 순서대로 확인한다.

---

## 1. 한눈에

| 순서 | 확인 | 기대 |
|---|---|---|
| 1 | Compose 버전 | `include` 지원 (v2.20 이상) |
| 2 | 저장소·`.env` | `~/chat`가 있고 `~/chat/.env`에 `JWT_SECRET`, `DB_*` |
| 3 | DB | `chat` 데이터베이스 존재 |
| 4 | 컨테이너 | `chat-app` healthy, 호스트 포트 publish 없음 |
| 5 | 내부 헬스 | `/actuator/health` UP |
| 6 | 라우팅 | REST 401, WebSocket 101, 외부 Origin 403 |
| 7 | 인증 정합 | board 로그인 토큰으로 `/me` 200 |
| 8 | E2E | 브라우저 두 개로 대화·입장·퇴장·로그아웃 |

---

## 2. 명령

```bash
# 1. Compose가 include를 지원하는가 (v2.20+)
docker compose version

# 2. chat 저장소와 .env
ls ~/chat/docker-compose.yml && cut -d= -f1 ~/chat/.env      # JWT_SECRET DB_NAME DB_USERNAME DB_PASSWORD

# 3. chat DB
docker exec mysql-8 mysql -uroot -p"$DB_PASSWORD" -e "SHOW DATABASES LIKE 'chat'"

# 4. 컨테이너 (chat-app healthy, PORTS에 0.0.0.0 없음)
cd ~/board && docker compose ps

# 5. 내부 헬스 (컨테이너 안에서 — 호스트 포트가 없으므로)
docker exec chat-app curl -s http://localhost:8092/actuator/health
#   {"groups":["liveness","readiness"],"status":"UP"}   ← MySQL·Redis 연결 성공

# 6. 라우팅 (caddy → nginx → chat-app)
curl -s -o /dev/null -w "%{http_code}\n" https://sbs.alldayai.org/api/v1/chat/rooms          # 401
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" -H "Origin: https://sbs.alldayai.org" \
  https://sbs.alldayai.org/ws                                                             # 101
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" -H "Origin: https://evil.example" \
  https://sbs.alldayai.org/ws                                                             # 403

# 7. 인증 정합 (JWT_SECRET이 board와 같은가)
TOKEN=$(curl -s -X POST https://sbs.alldayai.org/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin1234"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -s https://sbs.alldayai.org/api/v1/chat/me -H "Authorization: Bearer $TOKEN"
#   {"userId":1,"username":"admin","nickname":"관리자"}   ← 401 LOGIN_REQUIRED 면 secret 불일치
```

---

## 3. 증상별 원인

| 증상 | 원인 | 조치 |
|---|---|---|
| `docker compose` 자체가 실패, `include` 관련 오류 | Compose v2.20 미만 | 플러그인 업데이트 또는 board compose에 chat 서비스 블록을 직접 복사(`frontend_integration_scope.md` 4절 대안) |
| `pull`에서 chat-app 이미지 denied | GHCR 패키지가 private | 패키지 설정에서 public으로 (chat 저장소는 public) |
| `chat-app`이 healthy가 안 됨, 로그에 `Could not resolve placeholder 'JWT_SECRET'` | `~/chat/.env` 없음 또는 비어 있음 | `deploy.sh`의 `.env` 생성 블록, GitHub Secret `JWT_SECRET` |
| 로그에 `Unknown database 'chat'` | DB 미생성 | `deploy.sh`의 `CREATE DATABASE IF NOT EXISTS chat` |
| 인증 API가 500 | `board-redis`에 못 붙음 (fail-closed) | `docker compose ps`에서 redis healthy 확인, `depends_on` |
| `/ws`가 403 | Origin 불일치 | `APP_WS_ALLOWED_ORIGINS`에 `https://sbs.alldayai.org` 포함 여부 |
| `/ws`가 502/504 | nginx `/ws` 규칙(Upgrade 헤더) 누락 또는 `proxy_read_timeout` 짧음 | board `frontend/nginx.conf` |
| `/me`가 401인데 토큰은 방금 발급 | `JWT_SECRET` 불일치 | board `.env`와 `~/chat/.env`의 값 비교 |
| board 로그인 후 chat에서 계속 401 | board-app이 yaml 기본값, chat이 Secret 값 | board `.env`에도 `JWT_SECRET` 주입 |

---

## 4. 롤백

chat만 되돌릴 때: `cd ~/board && docker compose stop chat-app` (board 서비스는 그대로). 이미지 특정 버전으로: `~/chat/docker-compose.yml`의 `image:` 태그를 `ghcr.io/icesnake72/chat-app:<sha>`로 바꾸고 `docker compose up -d chat-app`. DB 스키마는 `ddl-auto: update`라 되돌리지 않는다(컬럼 추가만 있음).
