# 채팅 UI를 board 프론트엔드에 통합할 때의 기능 범위

> 기준: chat 서버 `main`(2일차까지, 2026-09-15). 채팅 UI는 chat 저장소가 아니라 board 프로젝트의 프론트엔드에 넣는다. 이 문서는 "board 프론트에 무엇까지 들어가야 채팅이 동작하는가"를 서버 API 계약 기준으로 정리한 것이다. 클라이언트 코드 참고는 `docs/plans/2026-09-15-day3-react-client.md`.

---

## 1. 핵심 요약

| 구분 | 내용 |
|---|---|
| 최소 동작 (필수) | 방 목록 → 방 생성 또는 입장(join) → 방 화면: 이력 1페이지 + STOMP CONNECT/SUBSCRIBE/SEND + 개인 에러 큐 |
| 이미 board에 있는 것 | 로그인·reissue·로그아웃, access 메모리 보관 + `Authorization: Bearer`, 401 → reissue 재시도 |
| 새로 붙일 인프라 | board nginx·Vite 프록시에 `/api/v1/chat/*` → chat-app:8092, `/ws` → chat-app:8092 (WebSocket upgrade) |
| 선택 | 로비 실시간 이벤트, 이전 이력 더보기, 멤버·접속 표시, 퇴장, 방 삭제, 선제 토큰 갱신 |

"방 만드는 작업까지"로는 부족하다. 방을 만들면 서버가 만든 사람을 멤버로 넣지만, **화면에서 메시지를 주고받으려면 방 화면(STOMP 연결·구독·전송)이 있어야** 한다. 방 생성만 있으면 빈 방 목록이 전부다.

---

## 2. 필수 기능과 서버 계약

### 2.1 REST (`/api/v1/chat`, 전부 Bearer 필수)

| 순서 | 기능 | 호출 | 응답 | 비고 |
|---|---|---|---|---|
| 1 | 내 정보 | `GET /me` | `{userId, username, nickname}` | 내 메시지 판별(`senderUserId`)과 닉네임 표시용. board의 `GET /profiles/me`로 대체 가능(같은 userId·nickname) |
| 2 | 방 목록 | `GET /rooms?page=0&size=20` | `PagedModel {content: RoomResponse[], page}` | `RoomResponse {id, name, description, ownerUsername, memberCount, onlineCount, createdAt}` |
| 3 | 방 생성 | `POST /rooms {name(≤50), description(≤200)?}` | 201 `RoomResponse` | 만든 사람 자동 입장. 409 `DUPLICATE_ROOM_NAME`, 400 `INVALID_INPUT` |
| 4 | 입장 | `POST /rooms/{id}/join` | 200 `RoomResponse` | 멱등. **구독·전송·이력 전에 반드시 호출** (비멤버는 403/ERROR) |
| 5 | 이력 | `GET /rooms/{id}/messages?size=50` | `{messages[] (오래된 순), hasMore, nextBefore}` | 방 화면 진입 시 1회 |

### 2.2 STOMP (`/ws`)

| 순서 | 프레임 | 값 | 비고 |
|---|---|---|---|
| 1 | CONNECT | 헤더 `Authorization: Bearer {access}` | 핸드셰이크 URL에는 토큰을 넣지 않는다. `@stomp/stompjs`의 `connectHeaders` |
| 2 | SUBSCRIBE | `/topic/rooms/{id}` | 구독 즉시 서버가 ENTER 시스템 메시지를 방에 뿌린다(입장 = 구독) |
| 3 | SUBSCRIBE | `/user/queue/errors` | 빈 메시지·1000자 초과·비멤버 전송 시 `ErrorResponse {code, message}`가 여기로 온다. 연결은 유지 |
| 4 | SEND | `/app/rooms/{id}/messages` 본문 `{"content":"..."}` | 응답 프레임 없음. 성공하면 방 토픽으로 MESSAGE가 돌아온다 |
| 5 | MESSAGE 수신 | `/topic/rooms/{id}` → `MessageResponse` | `{id, roomId, type(TALK/ENTER/LEAVE), senderUserId, senderUsername, senderNickname, content, createdAt}` |
| 6 | UNSUBSCRIBE / DISCONNECT | 방 화면을 떠날 때 | 서버가 LEAVE 시스템 메시지를 뿌린다 |

### 2.3 인증 에러 처리 (필수)

| 서버가 보내는 것 | 언제 | 클라이언트 |
|---|---|---|
| ERROR 프레임 `code: TOKEN_EXPIRED` + 연결 종료 | access 만료 후 첫 SEND/SUBSCRIBE | board의 reissue로 새 토큰 → 재연결 → 재구독 |
| ERROR 프레임 `code: LOGIN_REQUIRED` + 연결 종료 | 로그아웃(denylist), 토큰 무효 | 재시도 없이 로그아웃 처리 |
| ERROR 프레임 `code: NOT_ROOM_MEMBER` + 연결 종료 | join 없이 방 토픽 구독 | join 후 재연결 (정상 흐름에선 발생하지 않음) |
| REST 401 | access 만료 | board의 기존 인터셉터가 처리 (reissue 1회 재시도) |

재연결은 `@stomp/stompjs`가 자동으로 한다(`reconnectDelay`, `reconnectTimeMode: EXPONENTIAL`, `maxReconnectDelay`). 재연결 직전 `beforeConnect`에서 현재 access 토큰을 `connectHeaders`에 다시 넣으면 된다. 구독은 라이브러리가 복구하지 않으므로 `onConnect`에서 다시 건다.

---

## 3. 선택 기능

| 기능 | 호출 | 효과 |
|---|---|---|
| 로비 실시간 갱신 | SUBSCRIBE `/topic/rooms` → `RoomEvent {type: ROOM_CREATED/ROOM_DELETED/MEMBER_COUNT, room}` | 목록을 다시 GET 하지 않아도 방 생성·삭제·인원 변화가 반영 |
| 이전 이력 더보기 | `GET /rooms/{id}/messages?before={nextBefore}&size=50` | 위로 스크롤 시 50개씩 (keyset) |
| 멤버·접속 표시 | `GET /rooms/{id}/members` → `[{userId, username, online, joinedAt}]` | ENTER/LEAVE 수신 시 다시 조회하면 접속 점이 갱신 |
| 퇴장(멤버십 해제) | `DELETE /rooms/{id}/leave` | 방 목록의 "내 방"에서 빠짐. 구독 해제만으로는 멤버십이 남는다 |
| 방 삭제 | `DELETE /rooms/{id}` (소유자만, 403 `ACCESS_DENIED`) | 메시지·멤버까지 삭제, 로비에 ROOM_DELETED |
| 선제 토큰 갱신 | 만료 60초 전 reissue 후 재연결 | TOKEN_EXPIRED ERROR를 보지 않게 함. 없어도 동작은 한다 |

---

## 4. board 쪽에 필요한 인프라 변경 (board 저장소에서 작업)

| 위치 | 변경 | 이유 |
|---|---|---|
| `frontend/vite.config.js` (dev) | `'/api/v1/chat': { target: 'http://localhost:8092', changeOrigin: true }`, `'/ws': { target: 'http://localhost:8092', ws: true, changeOrigin: true }` | 개발 시 same-origin |
| `frontend/nginx.conf` (운영) | `location /api/v1/chat/ { proxy_pass http://$chat; ... }`, `location /ws { proxy_pass http://$chat; proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; proxy_read_timeout 3600s; }` (`set $chat chat-app:8092;` + 기존 `resolver`) | WebSocket upgrade 프록시 |
| `docker-compose.yml` | 변경 없음 (chat-app은 chat 저장소의 compose가 `board-db-net`에 합류) | 같은 네트워크라 컨테이너명으로 접근 |
| `caddy/Caddyfile` | 변경 없음 | 같은 도메인(`sbs.alldayai.org`)이므로 별도 블록 불필요 |

chat 서버 쪽 대응: 운영에서는 `APP_WS_ALLOWED_ORIGINS=https://sbs.alldayai.org`(Origin 검사). 로컬 Vite(5173)는 기본값 `http://localhost:*`로 통과한다. 별도 서브도메인·chat-frontend 컨테이너·caddy 블록 계획은 이 결정으로 필요 없어졌다.

---

## 5. 권장 구현 순서 (board 프론트 안에서)

| 단계 | 화면 | 완료 기준 |
|---|---|---|
| 1 | 프록시 2줄 + `GET /chat/rooms` 목록 페이지 | 로그인 상태에서 방 목록이 보인다 |
| 2 | 방 생성 폼 | 201 후 목록에 추가 |
| 3 | 방 화면: join → 이력 → STOMP CONNECT → `/topic/rooms/{id}`·`/user/queue/errors` 구독 → SEND | 두 브라우저에서 대화, 입장·퇴장 메시지 |
| 4 | TOKEN_EXPIRED 재연결, LOGIN_REQUIRED 로그아웃 | board 로그아웃 후 전송 시 로그인 페이지로 |
| 5 | (선택) 로비 이벤트, 더보기, 멤버 표시, 퇴장·삭제 | |

3단계까지가 "동작하는 채팅"의 최소이고, 4단계는 수업에서 보여 주기에 가치가 큰 부분이다.
