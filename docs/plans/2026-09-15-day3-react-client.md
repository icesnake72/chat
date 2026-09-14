# 3일차 React 채팅 클라이언트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** board 계정으로 로그인해 로비에서 방을 만들고 입장하며, 방 안에서 실시간으로 대화하고 이력을 거슬러 읽는 React 클라이언트를 완성한다. 토큰 만료·로그아웃·네트워크 단절에 대한 재연결 정책을 포함한다.

**Architecture:** `frontend/`는 Vite 8 + React 19 + JavaScript 단일 앱. HTTP는 axios 인스턴스(`client.js`, access 메모리 보관 + 401 reissue 인터셉터, board-frontend 이식), WebSocket은 `@stomp/stompjs`를 감싼 `chatSocket.js`(재연결·토큰 갱신 정책을 한 곳에). `AuthContext`가 로그인·세션 복원·선제 갱신을, `SocketContext`가 소켓 수명을 관리하고, 페이지는 두 컨텍스트와 훅만 쓴다. 개발 중에는 Vite 프록시로 same-origin을 만든다(`/api/v1/auth` → board caddy `http://localhost`, `/api/v1/chat`·`/ws` → chat `:8092`).

**Tech Stack:** Vite 8.3, React 19.3, react-router-dom 7.18, axios 1.20, @stomp/stompjs 7.3, oxlint 1.83, Vitest 5.0 (node 환경). 서버 쪽은 heartbeat 활성화 1건만 수정.

**Spec:** `docs/design/2026-09-12-stomp-chat-design.md` 2.4, 5.1(heartbeat), 5.2, 7.4(프론트 규칙), 10.2

## Global Constraints

- 1·2일차 Global Constraints 전부 적용(board 무수정, 2-space, 비밀값 금지).
- **JavaScript** (TypeScript 아님). 파일은 `.js`/`.jsx`. 훅·컴포넌트는 함수형.
- 단계 순서 = 파일 생성 순서 = 의존 방향(설정 → api → auth → ws → 페이지). walkthrough가 이 순서를 따른다. 각 Task 끝에서 `npm run lint && npm test && npm run build`가 통과해야 한다.
- localStorage/sessionStorage에 토큰을 두지 않는다. access는 `client.js` 모듈 변수, refresh는 브라우저 쿠키.
- 서버 API 계약은 2일차 그대로. 서버 수정은 Task 0(heartbeat)뿐.
- 로컬 board는 `http://localhost`(caddy) 경유. 8090은 호스트에 열려 있지 않다.
- 커밋 접두어 `feat:`/`test:`/`docs:`, 한국어. 각 Task 1커밋.

---

## 파일 구조 (생성 순서)

| # | 파일 | 책임 |
|---|---|---|
| 0 | `src/main/java/.../global/config/WebSocketConfig.java` (수정) | simple broker heartbeat 10s/10s + `TaskScheduler` |
| 1 | `frontend/package.json`, `vite.config.js`, `index.html`, `.oxlintrc.json`, `src/main.jsx`, `src/App.jsx`, `src/index.css` | 스캐폴드, 프록시, 린트, 테스트 러너 |
| 2 | `frontend/src/api/client.js` (+ `client.test.js`) | axios 인스턴스, 메모리 토큰, 401 → reissue 1회 재시도 |
| 3 | `frontend/src/api/auth.js`, `chat.js`, `src/lib/format.js` | REST 함수, 에러 메시지·날짜 |
| 4 | `frontend/src/auth/authContext.js`, `AuthContext.jsx`, `src/routes/ProtectedRoute.jsx` | 로그인·로그아웃·부팅 복원·선제 갱신 |
| 5 | `frontend/src/ws/chatSocket.js` (+ `chatSocket.test.js`) | stompjs 래퍼: 재연결·토큰 갱신·구독 복구 |
| 6 | `frontend/src/ws/socketContext.js`, `SocketProvider.jsx`, `useSubscription.js` | 소켓 수명과 구독 훅 |
| 7 | `frontend/src/components/Layout.jsx`, `pages/LoginPage.jsx`, `pages/LobbyPage.jsx`, `components/RoomList.jsx`, `CreateRoomForm.jsx` | 로그인·로비 |
| 8 | `frontend/src/pages/RoomPage.jsx`, `components/MessageList.jsx`, `MessageInput.jsx`, `MemberList.jsx`, `src/App.css` | 방 화면 |
| 9 | `frontend/src/App.jsx` (수정), `README.md` (수정), 설계 문서 2.2 정정 | 라우팅 완성, 실행 안내, E2E |
| 10 | `docs/lecture/day3_react_client_walkthrough.md` | 문서 |

---

### Task 0: 서버 heartbeat 활성화

**Files:** Modify `src/main/java/com/example/chat/global/config/WebSocketConfig.java`, Modify `src/test/java/com/example/chat/message/RoomChatIntegrationTest.java`

**왜**: 지금은 서버가 heartbeat를 보내지 않아(`0,0`) 클라이언트가 끊긴 연결을 감지하지 못한다. simple broker의 heartbeat는 `TaskScheduler`가 있어야 켜진다.

- [ ] `WebSocketConfig`에 스케줄러 빈과 heartbeat 설정 추가. `configureMessageBroker`를 다음으로 교체하고 빈 메서드를 추가한다.

```java
  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {10_000, 10_000})
        .setTaskScheduler(heartbeatScheduler());
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  // simple broker heartbeat 전용 스케줄러 (Boot의 기본 TaskScheduler는 @EnableScheduling 없이는 없다)
  @Bean
  public ThreadPoolTaskScheduler heartbeatScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("ws-heartbeat-");
    scheduler.initialize();
    return scheduler;
  }
```

import 추가: `org.springframework.context.annotation.Bean`, `org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler`.

- [ ] `RoomChatIntegrationTest.memberSeesEnterTalkAndLeaveOfAnotherMember`의 alice 연결 핸들러를 CONNECTED 헤더를 잡는 것으로 바꿔 heartbeat 협상을 확인한다.

```java
    CompletableFuture<StompHeaders> connected = new CompletableFuture<>();
    StompSession alice = connect("alice", new StompSessionHandlerAdapter() {
      @Override
      public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
        connected.complete(connectedHeaders);
      }
    });
    assertThat(connected.get(5, TimeUnit.SECONDS).getFirst("heart-beat")).isEqualTo("10000,10000");
```

- [ ] `./gradlew --no-daemon -q test` 84개 통과 → 커밋 `feat: STOMP heartbeat 10s/10s 활성화 (클라이언트 단절 감지용)`

---

### Task 1: 프론트 스캐폴드

**Files:** Create `frontend/package.json`, `frontend/vite.config.js`, `frontend/index.html`, `frontend/.oxlintrc.json`, `frontend/src/main.jsx`, `frontend/src/App.jsx`, `frontend/src/index.css`, `frontend/.gitignore`

`frontend/package.json` (버전은 2026-09-15 npm 최신 기준)

```json
{
  "name": "chat-frontend",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "lint": "oxlint src",
    "test": "vitest run"
  },
  "dependencies": {
    "@stomp/stompjs": "^7.3.0",
    "axios": "^1.20.0",
    "react": "^19.3.0",
    "react-dom": "^19.3.0",
    "react-router-dom": "^7.18.3"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "^6.1.1",
    "oxlint": "^1.83.0",
    "vite": "^8.3.0",
    "vitest": "^5.0.0"
  }
}
```

`frontend/vite.config.js`

```js
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버에서 same-origin을 만든다 (CORS 없음, refresh 쿠키 그대로).
// board는 로컬에서 caddy(:80) 경유, chat은 :8092. 운영에서는 nginx가 같은 규칙으로 프록시한다 (4일차).
const BOARD = process.env.VITE_DEV_BOARD_URL ?? 'http://localhost'
const CHAT = process.env.VITE_DEV_CHAT_URL ?? 'http://localhost:8092'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api/v1/auth': { target: BOARD, changeOrigin: true },
      '/api/v1/chat': { target: CHAT, changeOrigin: true },
      '/ws': { target: CHAT, ws: true, changeOrigin: true },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.js'],
  },
})
```

`frontend/index.html`

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>chat</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.jsx"></script>
  </body>
</html>
```

`frontend/.oxlintrc.json`

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "oxc"],
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

`frontend/.gitignore`

```
node_modules
dist
```

`frontend/src/main.jsx` (Provider는 4·6절에서 추가된다. 지금은 라우터만)

```jsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
```

`frontend/src/App.jsx` (자리표시. Task 9에서 라우팅으로 교체)

```jsx
export default function App() {
  return <p className="page-center">chat</p>
}
```

`frontend/src/index.css` (board-frontend 토큰 이식)

```css
:root {
  --bg: #f6f7f9;
  --surface: #ffffff;
  --border: #e3e6ea;
  --text: #1c2024;
  --muted: #6b7280;
  --primary: #2563eb;
  --primary-hover: #1d4ed8;
  --danger: #dc2626;
  --system: #8b5cf6;
  --radius: 10px;

  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'Apple SD Gothic Neo',
    'Malgun Gothic', sans-serif;
  color: var(--text);
  background: var(--bg);
  line-height: 1.55;
}

@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0f1114;
    --surface: #171a1f;
    --border: #2a2f37;
    --text: #e6e8eb;
    --muted: #9aa1ac;
    --primary: #3b82f6;
    --primary-hover: #60a5fa;
    --danger: #f87171;
    --system: #a78bfa;
  }
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  min-height: 100vh;
}

a {
  color: var(--primary);
  text-decoration: none;
}

h1 {
  font-size: 1.4rem;
  margin: 0 0 1rem;
}

.page-center {
  display: grid;
  place-items: center;
  min-height: 50vh;
  color: var(--muted);
}
```

- [ ] `cd frontend && npm install && npm run lint && npm test -- --passWithNoTests && npm run build` 통과, `npm run dev`로 `http://localhost:5173`에 "chat" 표시
- [ ] 루트 `.gitignore`에 이미 `frontend/node_modules/`, `frontend/dist/`가 있는지 확인(1일차에 추가됨)
- [ ] 커밋 `feat: 프론트 스캐폴드 — Vite 8 + React 19, dev 프록시(board caddy, chat 8092), oxlint, vitest`

---

### Task 2: `client.js` — 메모리 토큰 + 401 reissue

**Files:** Create `frontend/src/api/client.js`, Test `frontend/src/api/client.test.js`

**Interfaces (Produces):** `setAccessToken(token)`, `getAccessToken()`, `setOnAuthFailure(fn)`, `api` (axios 인스턴스, baseURL `/api/v1`), `reissueToken()` → `Promise<{accessToken, expiresIn}>`

`frontend/src/api/client.js`

```js
import axios from 'axios'

// access token은 메모리에만 (localStorage 금지 = XSS 안전). refresh는 httpOnly 쿠키라 JS가 만지지 않는다.
// board-frontend의 client.js를 이식하고, 만료 시각을 함께 보관하도록 reissue 응답 전체를 돌려준다.
let accessToken = null
let onAuthFailure = null

export function setAccessToken(token) {
  accessToken = token
}

export function getAccessToken() {
  return accessToken
}

// reissue까지 실패했을 때(=세션 만료) 호출될 콜백 등록
export function setOnAuthFailure(fn) {
  onAuthFailure = fn
}

export const api = axios.create({
  baseURL: '/api/v1',
  withCredentials: true, // refresh 쿠키 전송 (reissue/logout)
})

// refresh 쿠키(Path=/api/v1/auth)로 새 access 발급. 인터셉터 재귀를 피하려고 bare axios 사용.
export async function reissueToken() {
  const res = await axios.post('/api/v1/auth/reissue', null, { withCredentials: true })
  setAccessToken(res.data.accessToken)
  return res.data // { accessToken, tokenType, expiresIn }
}

// 요청: 메모리 토큰을 Authorization 헤더로 주입
api.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

// 응답: 401 → reissue 1회 → 원요청 재시도. 동시 401은 하나의 reissue를 공유한다.
let refreshing = null
api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const { response, config } = error
    const isAuthCall = config?.url?.includes('/auth/')
    if (response?.status === 401 && config && !config._retry && !isAuthCall) {
      config._retry = true
      try {
        refreshing = refreshing || reissueToken()
        const { accessToken: newToken } = await refreshing
        refreshing = null
        config.headers = config.headers || {}
        config.headers.Authorization = `Bearer ${newToken}`
        return api(config)
      } catch (e) {
        refreshing = null
        setAccessToken(null)
        if (onAuthFailure) onAuthFailure()
        return Promise.reject(e)
      }
    }
    return Promise.reject(error)
  },
)
```

`frontend/src/api/client.test.js` (네트워크 없이 axios adapter를 가짜로)

```js
import axios from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, getAccessToken, setAccessToken, setOnAuthFailure } from './client.js'

// URL별 응답 스크립트. 호출 순서대로 소비한다.
function scriptedAdapter(script) {
  const calls = []
  const adapter = async (config) => {
    calls.push({ url: config.url, auth: config.headers?.Authorization ?? config.headers?.get?.('Authorization') })
    const next = script[config.url]?.shift()
    if (!next) throw new Error(`no scripted response for ${config.url}`)
    const response = { status: next.status, data: next.data, headers: {}, config }
    if (next.status >= 400) {
      const err = new axios.AxiosError('http error', String(next.status), config, null, response)
      err.response = response
      throw err
    }
    return response
  }
  return { adapter, calls }
}

describe('client.js', () => {
  beforeEach(() => {
    setAccessToken(null)
    setOnAuthFailure(null)
  })

  it('injects the in-memory token as Bearer', async () => {
    const { adapter, calls } = scriptedAdapter({ '/chat/me': [{ status: 200, data: { username: 'alice' } }] })
    api.defaults.adapter = adapter
    setAccessToken('t1')

    const res = await api.get('/chat/me')

    expect(res.data.username).toBe('alice')
    expect(calls[0].auth).toBe('Bearer t1')
  })

  it('on 401 reissues once and retries with the new token', async () => {
    const { adapter, calls } = scriptedAdapter({
      '/chat/me': [{ status: 401, data: { code: 'LOGIN_REQUIRED' } }, { status: 200, data: { username: 'alice' } }],
    })
    api.defaults.adapter = adapter
    axios.defaults.adapter = async (config) => ({
      status: 200, data: { accessToken: 't2', tokenType: 'Bearer', expiresIn: 3600 }, headers: {}, config,
    })
    setAccessToken('t1')

    const res = await api.get('/chat/me')

    expect(res.data.username).toBe('alice')
    expect(getAccessToken()).toBe('t2')
    expect(calls.map((c) => c.auth)).toEqual(['Bearer t1', 'Bearer t2'])
  })

  it('calls onAuthFailure and clears token when reissue fails', async () => {
    const { adapter } = scriptedAdapter({ '/chat/me': [{ status: 401, data: { code: 'LOGIN_REQUIRED' } }] })
    api.defaults.adapter = adapter
    axios.defaults.adapter = async (config) => {
      const err = new axios.AxiosError('unauthorized', '401', config, null, { status: 401, data: {}, headers: {}, config })
      throw err
    }
    const onFailure = vi.fn()
    setOnAuthFailure(onFailure)
    setAccessToken('t1')

    await expect(api.get('/chat/me')).rejects.toBeTruthy()

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
  })

  it('does not retry auth endpoints', async () => {
    const { adapter, calls } = scriptedAdapter({ '/auth/logout': [{ status: 401, data: {} }] })
    api.defaults.adapter = adapter

    await expect(api.post('/auth/logout')).rejects.toBeTruthy()

    expect(calls).toHaveLength(1)
  })
})
```

> 주의: `config.headers`는 axios 1.x에서 `AxiosHeaders` 객체라 테스트에서 `.get('Authorization')`로도 읽는다. 첫 실행 시 어느 쪽이 맞는지 확인하고 한쪽으로 정리한다.

- [ ] `npm test` 4개 통과 → 커밋 `feat: axios 클라이언트 — 메모리 access 토큰 + 401 reissue 1회 재시도 (board-frontend 이식)`

---

### Task 3: `auth.js`, `chat.js`, `format.js`

`frontend/src/api/auth.js`

```js
import { api, reissueToken } from './client.js'

// board의 /api/v1/auth/** — Vite 프록시(로컬) / nginx(운영)가 board-app으로 넘긴다
export async function login({ username, password }) {
  const res = await api.post('/auth/login', { username, password })
  return res.data // { accessToken, tokenType, expiresIn }
}

export async function logout() {
  await api.post('/auth/logout')
}

export const reissue = reissueToken
```

`frontend/src/api/chat.js`

```js
import { api } from './client.js'

// chat 서버 REST (2일차 6절)
export async function me() {
  return (await api.get('/chat/me')).data // { userId, username, nickname }
}

export async function listRooms({ page = 0, size = 20 } = {}) {
  return (await api.get('/chat/rooms', { params: { page, size } })).data // PagedModel { content, page }
}

export async function createRoom({ name, description }) {
  return (await api.post('/chat/rooms', { name, description })).data // RoomResponse
}

export async function getRoom(roomId) {
  return (await api.get(`/chat/rooms/${roomId}`)).data
}

export async function joinRoom(roomId) {
  return (await api.post(`/chat/rooms/${roomId}/join`)).data
}

export async function leaveRoom(roomId) {
  await api.delete(`/chat/rooms/${roomId}/leave`)
}

export async function deleteRoom(roomId) {
  await api.delete(`/chat/rooms/${roomId}`)
}

export async function listMembers(roomId) {
  return (await api.get(`/chat/rooms/${roomId}/members`)).data // MemberResponse[]
}

export async function history(roomId, { before, size = 50 } = {}) {
  return (await api.get(`/chat/rooms/${roomId}/messages`, { params: { before, size } })).data
  // { messages (오래된 순), hasMore, nextBefore }
}
```

`frontend/src/lib/format.js`

```js
// 백엔드 에러 포맷 { code, message, timestamp, errors?: [{field, reason}] } → 사용자 문구
export function errorMessage(error, fallback = '요청 처리 중 오류가 발생했습니다.') {
  const data = error?.response?.data
  if (Array.isArray(data?.errors) && data.errors.length > 0) {
    return data.errors.map((e) => e.reason || e.field).join(' · ')
  }
  return data?.message || error?.message || fallback
}

export function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  return d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}
```

- [ ] `npm run lint && npm run build` → 커밋 `feat: auth·chat REST 함수 + 에러 메시지·시각 포맷`

---

### Task 4: `AuthContext` — 로그인·복원·선제 갱신

**Files:** Create `frontend/src/auth/authContext.js`, `frontend/src/auth/AuthContext.jsx`, `frontend/src/routes/ProtectedRoute.jsx`; Modify `frontend/src/main.jsx`

**Interfaces (Produces):** `useAuth()` → `{ user, booting, isAuthenticated, login(credentials), logout(), refresh() }`. `refresh()`는 reissue 후 새 토큰을 적용하고 `expiresIn`을 돌려준다(6절 소켓이 재연결 전에 쓴다).

`frontend/src/auth/authContext.js`

```js
import { createContext, useContext } from 'react'

export const AuthContext = createContext(null)

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
```

`frontend/src/auth/AuthContext.jsx`

```jsx
import { useCallback, useEffect, useRef, useState } from 'react'
import { setAccessToken, setOnAuthFailure } from '../api/client.js'
import * as authApi from '../api/auth.js'
import * as chatApi from '../api/chat.js'
import { AuthContext } from './authContext.js'

const REFRESH_MARGIN_MS = 60_000 // 만료 60초 전에 선제 갱신

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null) // { userId, username, nickname }
  const [booting, setBooting] = useState(true)
  const timerRef = useRef(null)

  const clearTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }

  // 토큰 적용 + 만료 전 자동 갱신 예약. 갱신 후 소켓 재연결은 SocketProvider가 user/token 변화를 보고 한다.
  const applyToken = useCallback((accessToken, expiresIn) => {
    setAccessToken(accessToken)
    clearTimer()
    if (accessToken && expiresIn) {
      timerRef.current = setTimeout(
        () => refresh().catch(() => {}),
        Math.max(expiresIn * 1000 - REFRESH_MARGIN_MS, 5_000),
      )
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const refresh = useCallback(async () => {
    const data = await authApi.reissue()
    applyToken(data.accessToken, data.expiresIn)
    return data
  }, [applyToken])

  const signOut = useCallback(() => {
    applyToken(null, null)
    setUser(null)
  }, [applyToken])

  const login = useCallback(
    async (credentials) => {
      const data = await authApi.login(credentials)
      applyToken(data.accessToken, data.expiresIn)
      setUser(await chatApi.me())
    },
    [applyToken],
  )

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } catch {
      // 서버 로그아웃 실패해도 클라이언트 상태는 정리
    }
    signOut()
  }, [signOut])

  // 부팅: refresh 쿠키로 세션 복원 → /me
  useEffect(() => {
    setOnAuthFailure(signOut)
    let alive = true
    ;(async () => {
      try {
        await refresh()
        const me = await chatApi.me()
        if (alive) setUser(me)
      } catch {
        // 비로그인 — 정상
      } finally {
        if (alive) setBooting(false)
      }
    })()
    return () => {
      alive = false
      clearTimer()
    }
  }, [refresh, signOut])

  const value = { user, booting, isAuthenticated: !!user, login, logout, refresh }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
```

`frontend/src/routes/ProtectedRoute.jsx`

```jsx
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/authContext.js'

export default function ProtectedRoute({ children }) {
  const { isAuthenticated, booting } = useAuth()
  const location = useLocation()

  if (booting) return <div className="page-center">세션 확인 중…</div>
  if (!isAuthenticated) return <Navigate to="/login" replace state={{ from: location }} />
  return children
}
```

`main.jsx`의 `<BrowserRouter>` 안을 `<AuthProvider><App /></AuthProvider>`로 감싼다(import 추가).

- [ ] `npm run lint && npm run build` → 커밋 `feat: AuthContext — 로그인·로그아웃·부팅 복원·만료 60초 전 선제 갱신`

---

### Task 5: `chatSocket.js` — stompjs 래퍼

**Files:** Create `frontend/src/ws/chatSocket.js`, Test `frontend/src/ws/chatSocket.test.js`

**Interfaces (Produces):** `createChatSocket({ getToken, onAuthFailure, onTokenExpired, brokerURL?, clientFactory? })` → `{ activate(), deactivate(), reconnect(), subscribe(destination, handler) → unsubscribe, publish(destination, body), onStateChange(fn) → off, get state }`. `state`는 `'disconnected' | 'connecting' | 'connected'`. handler는 파싱된 JSON 객체를 받는다.

`frontend/src/ws/chatSocket.js`

```js
import { Client, ReconnectionTimeMode } from '@stomp/stompjs'

function defaultBrokerURL() {
  const proto = window.location.protocol === 'https:' ? 'wss://' : 'ws://'
  return proto + window.location.host + '/ws'
}

// STOMP 연결의 수명·재연결·토큰 정책을 한 곳에. React는 이 객체의 state와 subscribe만 쓴다.
//   TOKEN_EXPIRED  → onTokenExpired()(reissue) 후 라이브러리 자동 재연결, beforeConnect가 새 토큰을 싣는다
//   LOGIN_REQUIRED → 재시도 없이 deactivate + onAuthFailure()
//   그 외 단절     → 1s → 2s → … 30s 지수 백오프 (라이브러리)
export function createChatSocket({
  getToken,
  onAuthFailure,
  onTokenExpired,
  brokerURL = defaultBrokerURL(),
  clientFactory = (config) => new Client(config),
}) {
  let state = 'disconnected'
  const stateListeners = new Set()
  const subscriptions = new Map() // id -> { destination, handler, stompSub }
  let nextId = 1

  const setState = (next) => {
    if (state === next) return
    state = next
    stateListeners.forEach((fn) => fn(next))
  }

  const client = clientFactory({
    brokerURL,
    reconnectDelay: 1_000,
    maxReconnectDelay: 30_000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    beforeConnect: async (c) => {
      setState('connecting')
      const token = await getToken()
      if (!token) {
        await c.deactivate()
        onAuthFailure()
        return
      }
      c.configure({ connectHeaders: { Authorization: `Bearer ${token}` } })
    },
    onConnect: () => {
      // 재연결 시 기존 구독을 복구한다
      subscriptions.forEach((sub) => {
        sub.stompSub = client.subscribe(sub.destination, (message) => sub.handler(JSON.parse(message.body)))
      })
      setState('connected')
    },
    onStompError: (frame) => {
      const code = frame.headers.code
      if (code === 'LOGIN_REQUIRED' || code === 'NOT_ROOM_MEMBER') {
        client.deactivate()
        setState('disconnected')
        if (code === 'LOGIN_REQUIRED') onAuthFailure()
        return
      }
      if (code === 'TOKEN_EXPIRED') {
        onTokenExpired() // reissue → 라이브러리가 재연결 → beforeConnect가 새 토큰 사용
      }
    },
    onWebSocketClose: () => {
      subscriptions.forEach((sub) => {
        sub.stompSub = null
      })
      setState('disconnected')
    },
  })

  return {
    get state() {
      return state
    },
    activate() {
      client.activate()
    },
    async deactivate() {
      await client.deactivate()
      setState('disconnected')
    },
    // 토큰을 갱신한 뒤 새 토큰으로 다시 붙을 때 (선제 갱신)
    async reconnect() {
      await client.deactivate()
      client.activate()
    },
    subscribe(destination, handler) {
      const id = nextId++
      const sub = { destination, handler, stompSub: null }
      subscriptions.set(id, sub)
      if (state === 'connected') {
        sub.stompSub = client.subscribe(destination, (message) => handler(JSON.parse(message.body)))
      }
      return () => {
        subscriptions.delete(id)
        if (sub.stompSub) sub.stompSub.unsubscribe()
      }
    },
    publish(destination, body) {
      client.publish({ destination, body: JSON.stringify(body) })
    },
    onStateChange(fn) {
      stateListeners.add(fn)
      return () => stateListeners.delete(fn)
    },
  }
}
```

`frontend/src/ws/chatSocket.test.js` (가짜 클라이언트로 정책만 검증)

```js
import { describe, expect, it, vi } from 'vitest'
import { createChatSocket } from './chatSocket.js'

// stompjs Client를 흉내: 설정을 기억하고, 테스트가 콜백을 직접 호출한다
function fakeClientFactory() {
  let cfg
  const client = {
    activate: vi.fn(),
    deactivate: vi.fn(async () => {}),
    configure: vi.fn((patch) => Object.assign(cfg, patch)),
    subscribe: vi.fn(() => ({ unsubscribe: vi.fn() })),
    publish: vi.fn(),
  }
  const factory = (config) => {
    cfg = config
    return client
  }
  return { factory, client, config: () => cfg }
}

describe('chatSocket', () => {
  it('puts a fresh token into CONNECT headers before each connect', async () => {
    const { factory, client, config } = fakeClientFactory()
    const socket = createChatSocket({
      getToken: async () => 'tok-1', onAuthFailure: vi.fn(), onTokenExpired: vi.fn(), brokerURL: 'ws://x/ws', clientFactory: factory,
    })

    await config().beforeConnect(client)

    expect(config().connectHeaders.Authorization).toBe('Bearer tok-1')
    expect(socket.state).toBe('connecting')
  })

  it('restores subscriptions on reconnect', () => {
    const { factory, client, config } = fakeClientFactory()
    const socket = createChatSocket({
      getToken: async () => 't', onAuthFailure: vi.fn(), onTokenExpired: vi.fn(), brokerURL: 'ws://x/ws', clientFactory: factory,
    })
    const handler = vi.fn()
    socket.subscribe('/topic/rooms/7', handler)
    expect(client.subscribe).not.toHaveBeenCalled() // 아직 미연결

    config().onConnect()
    expect(client.subscribe).toHaveBeenCalledTimes(1)
    const [dest, cb] = client.subscribe.mock.calls[0]
    expect(dest).toBe('/topic/rooms/7')
    cb({ body: '{"type":"TALK"}' })
    expect(handler).toHaveBeenCalledWith({ type: 'TALK' })

    config().onWebSocketClose()
    config().onConnect()
    expect(client.subscribe).toHaveBeenCalledTimes(2)
    expect(socket.state).toBe('connected')
  })

  it('LOGIN_REQUIRED stops the client and reports auth failure', () => {
    const { factory, client, config } = fakeClientFactory()
    const onAuthFailure = vi.fn()
    createChatSocket({
      getToken: async () => 't', onAuthFailure, onTokenExpired: vi.fn(), brokerURL: 'ws://x/ws', clientFactory: factory,
    })

    config().onStompError({ headers: { code: 'LOGIN_REQUIRED' } })

    expect(client.deactivate).toHaveBeenCalled()
    expect(onAuthFailure).toHaveBeenCalledTimes(1)
  })

  it('TOKEN_EXPIRED asks for a refresh and lets the library reconnect', () => {
    const { factory, client, config } = fakeClientFactory()
    const onTokenExpired = vi.fn()
    createChatSocket({
      getToken: async () => 't', onAuthFailure: vi.fn(), onTokenExpired, brokerURL: 'ws://x/ws', clientFactory: factory,
    })

    config().onStompError({ headers: { code: 'TOKEN_EXPIRED' } })

    expect(onTokenExpired).toHaveBeenCalledTimes(1)
    expect(client.deactivate).not.toHaveBeenCalled()
    expect(config().reconnectDelay).toBe(1_000)
    expect(config().maxReconnectDelay).toBe(30_000)
  })

  it('missing token deactivates and reports auth failure', async () => {
    const { factory, client, config } = fakeClientFactory()
    const onAuthFailure = vi.fn()
    createChatSocket({
      getToken: async () => null, onAuthFailure, onTokenExpired: vi.fn(), brokerURL: 'ws://x/ws', clientFactory: factory,
    })

    await config().beforeConnect(client)

    expect(client.deactivate).toHaveBeenCalled()
    expect(onAuthFailure).toHaveBeenCalledTimes(1)
  })
})
```

- [ ] `npm test` 9개 통과 → 커밋 `feat: chatSocket — stompjs 래퍼 (토큰 갱신 재연결, 구독 복구, LOGIN_REQUIRED 중단, 지수 백오프)`

---

### Task 6: `SocketProvider`, `useSubscription`

**Files:** Create `frontend/src/ws/socketContext.js`, `frontend/src/ws/SocketProvider.jsx`, `frontend/src/ws/useSubscription.js`; Modify `frontend/src/main.jsx`

`frontend/src/ws/socketContext.js`

```js
import { createContext, useContext } from 'react'

export const SocketContext = createContext(null)

export function useSocket() {
  const ctx = useContext(SocketContext)
  if (!ctx) throw new Error('useSocket must be used within SocketProvider')
  return ctx
}
```

`frontend/src/ws/SocketProvider.jsx`

```jsx
import { useEffect, useMemo, useState } from 'react'
import { getAccessToken } from '../api/client.js'
import { useAuth } from '../auth/authContext.js'
import { createChatSocket } from './chatSocket.js'
import { SocketContext } from './socketContext.js'

// 로그인 상태일 때만 소켓을 살린다. 토큰이 갱신되면(AuthContext.refresh) 새 토큰으로 재연결한다.
export function SocketProvider({ children }) {
  const { isAuthenticated, refresh, logout } = useAuth()
  const [state, setState] = useState('disconnected')

  const socket = useMemo(
    () =>
      createChatSocket({
        // 메모리 토큰이 있으면 그대로, 없으면 reissue 시도 (새로고침 직후 등)
        getToken: async () => getAccessToken() ?? (await refresh().then((d) => d.accessToken).catch(() => null)),
        onAuthFailure: () => logout(),
        onTokenExpired: () => refresh().catch(() => logout()),
      }),
    [refresh, logout],
  )

  useEffect(() => socket.onStateChange(setState), [socket])

  useEffect(() => {
    if (!isAuthenticated) return undefined
    socket.activate()
    return () => {
      socket.deactivate()
    }
  }, [socket, isAuthenticated])

  const value = useMemo(() => ({ socket, state }), [socket, state])
  return <SocketContext.Provider value={value}>{children}</SocketContext.Provider>
}
```

`frontend/src/ws/useSubscription.js`

```js
import { useEffect, useRef } from 'react'
import { useSocket } from './socketContext.js'

// destination 구독. 연결 전에 불러도 chatSocket이 연결 후 붙인다. handler는 최신 클로저를 쓴다.
export function useSubscription(destination, handler) {
  const { socket } = useSocket()
  const handlerRef = useRef(handler)
  handlerRef.current = handler

  useEffect(() => {
    if (!destination) return undefined
    return socket.subscribe(destination, (payload) => handlerRef.current(payload))
  }, [socket, destination])
}
```

`main.jsx`: `<AuthProvider><SocketProvider><App /></SocketProvider></AuthProvider>`.

> 선제 갱신 후 재연결: `AuthContext.refresh`는 토큰만 바꾼다. 기존 연결은 옛 토큰의 `exp`로 세션이 살아 있으므로, 서버가 만료 시점에 첫 SEND/SUBSCRIBE에서 `TOKEN_EXPIRED`를 내면 `onTokenExpired` → (이미 갱신된 토큰으로) 라이브러리 재연결로 회복된다. 만료 전 무중단 재연결이 필요하면 `refresh` 직후 `socket.reconnect()`를 호출하는 한 줄을 `SocketProvider`에 추가한다(수업에서는 선택).

- [ ] `npm run lint && npm run build` → 커밋 `feat: SocketProvider + useSubscription — 로그인 상태에 소켓 수명 결합`

---

### Task 7: 로그인·로비

**Files:** Create `frontend/src/components/Layout.jsx`, `frontend/src/pages/LoginPage.jsx`, `frontend/src/pages/LobbyPage.jsx`, `frontend/src/components/RoomList.jsx`, `frontend/src/components/CreateRoomForm.jsx`

`frontend/src/components/Layout.jsx`

```jsx
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/authContext.js'
import { useSocket } from '../ws/socketContext.js'

export default function Layout({ children }) {
  const { user, isAuthenticated, logout } = useAuth()
  const { state } = useSocket()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="brand">chat</Link>
        <nav className="nav">
          {isAuthenticated ? (
            <>
              <span className={`conn conn-${state}`} title={state}>{user.nickname}</span>
              <button className="btn btn-ghost" onClick={handleLogout}>로그아웃</button>
            </>
          ) : (
            <Link className="btn btn-ghost" to="/login">로그인</Link>
          )}
        </nav>
      </header>
      <main className="app-main">{children}</main>
    </div>
  )
}
```

`frontend/src/pages/LoginPage.jsx`

```jsx
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/authContext.js'
import { errorMessage } from '../lib/format.js'

// 회원가입은 board 사이트에서. 로컬은 caddy(http://localhost), 운영은 sbs.alldayai.org
const BOARD_URL = import.meta.env.VITE_BOARD_URL ?? 'http://localhost'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = location.state?.from?.pathname || '/'

  const [form, setForm] = useState({ username: '', password: '' })
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  const onChange = (e) => setForm((f) => ({ ...f, [e.target.name]: e.target.value }))

  const onSubmit = async (e) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await login(form)
      navigate(from, { replace: true })
    } catch (err) {
      setError(errorMessage(err, '로그인에 실패했습니다.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="card auth-card">
      <h1>로그인</h1>
      <p className="muted">board 계정으로 로그인합니다.</p>
      <form onSubmit={onSubmit} className="form">
        <label className="field">
          <span>아이디</span>
          <input name="username" value={form.username} onChange={onChange} autoComplete="username" required />
        </label>
        <label className="field">
          <span>비밀번호</span>
          <input name="password" type="password" value={form.password} onChange={onChange} autoComplete="current-password" required />
        </label>
        {error && <p className="error-text">{error}</p>}
        <button className="btn btn-primary" disabled={submitting}>{submitting ? '로그인 중…' : '로그인'}</button>
      </form>
      <p className="muted small">
        계정이 없으신가요? <a href={`${BOARD_URL}/signup`} target="_blank" rel="noreferrer">board에서 회원가입</a>
      </p>
    </section>
  )
}
```

`frontend/src/components/CreateRoomForm.jsx`

```jsx
import { useState } from 'react'
import * as chatApi from '../api/chat.js'
import { errorMessage } from '../lib/format.js'

export default function CreateRoomForm({ onCreated }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const onSubmit = async (e) => {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const room = await chatApi.createRoom({ name: name.trim(), description: description.trim() || null })
      setName('')
      setDescription('')
      onCreated(room)
    } catch (err) {
      setError(errorMessage(err, '방을 만들지 못했습니다.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={onSubmit} className="form form-inline">
      <input placeholder="방 이름 (50자 이내)" value={name} onChange={(e) => setName(e.target.value)} maxLength={50} required />
      <input placeholder="설명 (선택)" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={200} />
      <button className="btn btn-primary" disabled={busy}>{busy ? '만드는 중…' : '방 만들기'}</button>
      {error && <p className="error-text">{error}</p>}
    </form>
  )
}
```

`frontend/src/components/RoomList.jsx`

```jsx
import { Link } from 'react-router-dom'

export default function RoomList({ rooms, onJoin }) {
  if (rooms.length === 0) return <p className="muted">아직 방이 없습니다. 첫 방을 만들어 보세요.</p>
  return (
    <ul className="room-list">
      {rooms.map((room) => (
        <li key={room.id} className="card room-item">
          <div>
            <h3><Link to={`/rooms/${room.id}`}>{room.name}</Link></h3>
            <p className="muted small">{room.description || '설명 없음'} · 멤버 {room.memberCount} · 접속 {room.onlineCount} · by {room.ownerUsername}</p>
          </div>
          <button className="btn" onClick={() => onJoin(room)}>입장</button>
        </li>
      ))}
    </ul>
  )
}
```

`frontend/src/pages/LobbyPage.jsx`

```jsx
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import * as chatApi from '../api/chat.js'
import CreateRoomForm from '../components/CreateRoomForm.jsx'
import RoomList from '../components/RoomList.jsx'
import { errorMessage } from '../lib/format.js'
import { useSubscription } from '../ws/useSubscription.js'

// 목록은 REST로 한 번 받고, 이후 변화는 /topic/rooms 의 RoomEvent로 반영한다 (폴링 없음)
export default function LobbyPage() {
  const navigate = useNavigate()
  const [rooms, setRooms] = useState([])
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      const page = await chatApi.listRooms({ size: 50 })
      setRooms(page.content)
    } catch (err) {
      setError(errorMessage(err))
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  useSubscription('/topic/rooms', (event) => {
    setRooms((prev) => {
      if (event.type === 'ROOM_DELETED') return prev.filter((r) => r.id !== event.room.id)
      const exists = prev.some((r) => r.id === event.room.id)
      if (event.type === 'ROOM_CREATED' && !exists) return [event.room, ...prev]
      return prev.map((r) => (r.id === event.room.id ? event.room : r))
    })
  })

  const join = async (room) => {
    try {
      await chatApi.joinRoom(room.id)
      navigate(`/rooms/${room.id}`)
    } catch (err) {
      setError(errorMessage(err, '입장하지 못했습니다.'))
    }
  }

  return (
    <section>
      <h1>로비</h1>
      <CreateRoomForm onCreated={(room) => navigate(`/rooms/${room.id}`)} />
      {error && <p className="error-text">{error}</p>}
      <RoomList rooms={rooms} onJoin={join} />
    </section>
  )
}
```

- [ ] `npm run lint && npm run build` → 커밋 `feat: 로그인·로비 화면 — REST 목록 + /topic/rooms 실시간 반영`

---

### Task 8: 방 화면

**Files:** Create `frontend/src/pages/RoomPage.jsx`, `frontend/src/components/MessageList.jsx`, `frontend/src/components/MessageInput.jsx`, `frontend/src/components/MemberList.jsx`, `frontend/src/App.css`

`frontend/src/components/MessageList.jsx`

```jsx
import { useEffect, useRef } from 'react'
import { formatTime } from '../lib/format.js'

export default function MessageList({ messages, me, hasMore, onLoadOlder, loadingOlder }) {
  const bottomRef = useRef(null)
  const lastIdRef = useRef(null)

  // 새 메시지가 "뒤에" 붙었을 때만 아래로 스크롤 (이전 이력을 앞에 붙일 땐 유지)
  useEffect(() => {
    const last = messages[messages.length - 1]?.id ?? null
    if (last !== lastIdRef.current) {
      lastIdRef.current = last
      bottomRef.current?.scrollIntoView({ block: 'end' })
    }
  }, [messages])

  return (
    <div className="messages">
      {hasMore && (
        <button className="btn btn-ghost small" onClick={onLoadOlder} disabled={loadingOlder}>
          {loadingOlder ? '불러오는 중…' : '이전 메시지 더 보기'}
        </button>
      )}
      {messages.map((m) => {
        if (m.type !== 'TALK') return <p key={m.id} className="msg-system">{m.content}</p>
        const mine = m.senderUserId === me.userId
        return (
          <div key={m.id} className={`msg ${mine ? 'msg-mine' : ''}`}>
            <span className="msg-meta">{mine ? '나' : m.senderNickname} · {formatTime(m.createdAt)}</span>
            <span className="msg-body">{m.content}</span>
          </div>
        )
      })}
      <div ref={bottomRef} />
    </div>
  )
}
```

`frontend/src/components/MessageInput.jsx`

```jsx
import { useState } from 'react'

export default function MessageInput({ onSend, disabled }) {
  const [text, setText] = useState('')

  const submit = (e) => {
    e.preventDefault()
    const content = text.trim()
    if (!content) return
    onSend(content)
    setText('')
  }

  return (
    <form onSubmit={submit} className="form-inline msg-input">
      <input value={text} onChange={(e) => setText(e.target.value)} placeholder={disabled ? '연결 중…' : '메시지 (1000자 이내)'} maxLength={1000} disabled={disabled} autoFocus />
      <button className="btn btn-primary" disabled={disabled || !text.trim()}>보내기</button>
    </form>
  )
}
```

`frontend/src/components/MemberList.jsx`

```jsx
export default function MemberList({ members }) {
  return (
    <aside className="members">
      <h3>멤버 {members.length}</h3>
      <ul>
        {members.map((m) => (
          <li key={m.userId} className={m.online ? 'online' : 'offline'}>
            <span className="dot" /> {m.username}
          </li>
        ))}
      </ul>
    </aside>
  )
}
```

`frontend/src/pages/RoomPage.jsx`

```jsx
import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import * as chatApi from '../api/chat.js'
import { useAuth } from '../auth/authContext.js'
import MemberList from '../components/MemberList.jsx'
import MessageInput from '../components/MessageInput.jsx'
import MessageList from '../components/MessageList.jsx'
import { errorMessage } from '../lib/format.js'
import { useSocket } from '../ws/socketContext.js'
import { useSubscription } from '../ws/useSubscription.js'

export default function RoomPage() {
  const { roomId } = useParams()
  const { user } = useAuth()
  const { socket, state } = useSocket()
  const navigate = useNavigate()

  const [room, setRoom] = useState(null)
  const [messages, setMessages] = useState([])
  const [hasMore, setHasMore] = useState(false)
  const [nextBefore, setNextBefore] = useState(null)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [members, setMembers] = useState([])
  const [error, setError] = useState(null)

  const refreshMembers = useCallback(() => chatApi.listMembers(roomId).then(setMembers).catch(() => {}), [roomId])

  // 입장 보장 → 방 정보 → 최근 이력 → 멤버
  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const info = await chatApi.joinRoom(roomId)
        const page = await chatApi.history(roomId, { size: 50 })
        if (!alive) return
        setRoom(info)
        setMessages(page.messages)
        setHasMore(page.hasMore)
        setNextBefore(page.nextBefore)
        refreshMembers()
      } catch (err) {
        if (alive) setError(errorMessage(err, '방을 열 수 없습니다.'))
      }
    })()
    return () => {
      alive = false
    }
  }, [roomId, refreshMembers])

  // 실시간: 방 토픽 (TALK / ENTER / LEAVE)
  useSubscription(`/topic/rooms/${roomId}`, (message) => {
    setMessages((prev) => (prev.some((m) => m.id === message.id) ? prev : [...prev, message]))
    if (message.type !== 'TALK') refreshMembers()
  })

  // 개인 에러 큐 (검증·권한 실패 — 연결은 유지된다)
  useSubscription('/user/queue/errors', (err) => setError(err.message))

  const send = (content) => {
    setError(null)
    socket.publish(`/app/rooms/${roomId}/messages`, { content })
  }

  const loadOlder = async () => {
    setLoadingOlder(true)
    try {
      const page = await chatApi.history(roomId, { before: nextBefore, size: 50 })
      setMessages((prev) => [...page.messages, ...prev])
      setHasMore(page.hasMore)
      setNextBefore(page.nextBefore)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setLoadingOlder(false)
    }
  }

  const leave = async () => {
    await chatApi.leaveRoom(roomId).catch(() => {})
    navigate('/')
  }

  if (!room && !error) return <div className="page-center">방을 여는 중…</div>

  return (
    <section className="room">
      <header className="room-header">
        <div>
          <Link to="/" className="muted small">← 로비</Link>
          <h1>{room?.name ?? '방'}</h1>
          {room?.description && <p className="muted small">{room.description}</p>}
        </div>
        <button className="btn btn-ghost" onClick={leave}>나가기</button>
      </header>
      {error && <p className="error-text">{error}</p>}
      <div className="room-body">
        <div className="room-main">
          <MessageList messages={messages} me={user} hasMore={hasMore} onLoadOlder={loadOlder} loadingOlder={loadingOlder} />
          <MessageInput onSend={send} disabled={state !== 'connected'} />
        </div>
        <MemberList members={members} />
      </div>
    </section>
  )
}
```

`frontend/src/App.css` (헤더·카드·폼·버튼은 board-frontend와 같은 톤, 채팅 레이아웃 추가)

```css
.app { min-height: 100vh; }
.app-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0.75rem 1.25rem; background: var(--surface); border-bottom: 1px solid var(--border);
  position: sticky; top: 0; z-index: 10;
}
.brand { font-weight: 700; font-size: 1.2rem; color: var(--text); }
.nav { display: flex; gap: 0.5rem; align-items: center; }
.conn::before { content: '●'; margin-right: 0.35rem; color: var(--muted); }
.conn-connected::before { color: #16a34a; }
.conn-connecting::before { color: #f59e0b; }
.app-main { max-width: 960px; margin: 0 auto; padding: 1.5rem 1.25rem 3rem; }

.card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 1rem 1.25rem; }
.auth-card { max-width: 380px; margin: 3rem auto; }
.muted { color: var(--muted); }
.small { font-size: 0.85rem; }
.error-text { color: var(--danger); margin: 0.5rem 0; }

.form { display: grid; gap: 0.75rem; }
.form-inline { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 1rem; }
.form-inline input { flex: 1 1 180px; }
.field { display: grid; gap: 0.3rem; }
input { padding: 0.5rem 0.65rem; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--text); font: inherit; }

.btn { display: inline-flex; align-items: center; gap: 0.4rem; padding: 0.45rem 0.9rem; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--text); font: inherit; cursor: pointer; }
.btn:hover { border-color: var(--primary); }
.btn:disabled { opacity: 0.55; cursor: not-allowed; }
.btn-ghost { background: transparent; border-color: transparent; }
.btn-primary { background: var(--primary); border-color: var(--primary); color: #fff; }
.btn-primary:hover { background: var(--primary-hover); }

.room-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 0.75rem; }
.room-item { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
.room-item h3 { margin: 0 0 0.2rem; }

.room-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; }
.room-body { display: grid; grid-template-columns: 1fr 200px; gap: 1rem; }
@media (max-width: 720px) { .room-body { grid-template-columns: 1fr; } }
.room-main { display: flex; flex-direction: column; min-height: 60vh; }
.messages { flex: 1; overflow-y: auto; max-height: 60vh; padding: 0.75rem; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); display: flex; flex-direction: column; gap: 0.5rem; }
.msg { display: flex; flex-direction: column; max-width: 80%; }
.msg-mine { align-self: flex-end; text-align: right; }
.msg-meta { font-size: 0.75rem; color: var(--muted); }
.msg-body { padding: 0.45rem 0.7rem; border-radius: var(--radius); background: var(--bg); white-space: pre-wrap; word-break: break-word; }
.msg-mine .msg-body { background: var(--primary); color: #fff; }
.msg-system { text-align: center; font-size: 0.8rem; color: var(--system); margin: 0; }
.msg-input { margin: 0.75rem 0 0; }
.members h3 { margin: 0 0 0.5rem; }
.members ul { list-style: none; padding: 0; margin: 0; display: grid; gap: 0.3rem; }
.members .dot::before { content: '●'; color: var(--muted); }
.members .online .dot::before { color: #16a34a; }
```

`App.jsx`에서 `import './App.css'`는 Task 9에서 넣는다.

- [ ] `npm run lint && npm run build` → 커밋 `feat: 방 화면 — 이력 keyset 더보기, 실시간 메시지, 멤버·접속 표시, 개인 에러 큐`

---

### Task 9: 라우팅 완성, README, 설계 정정, E2E

**Files:** Modify `frontend/src/App.jsx`, `README.md`, `docs/design/2026-09-12-stomp-chat-design.md`

`frontend/src/App.jsx`

```jsx
import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout.jsx'
import LobbyPage from './pages/LobbyPage.jsx'
import LoginPage from './pages/LoginPage.jsx'
import RoomPage from './pages/RoomPage.jsx'
import ProtectedRoute from './routes/ProtectedRoute.jsx'
import './App.css'

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<ProtectedRoute><LobbyPage /></ProtectedRoute>} />
        <Route path="/rooms/:roomId" element={<ProtectedRoute><RoomPage /></ProtectedRoute>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  )
}
```

README "로컬 실행"에 프론트 항목 추가:

```markdown
5. 프론트: `cd frontend && npm install && npm run dev` → `http://localhost:5173` (dev 프록시: `/api/v1/auth` → `http://localhost`(board caddy), `/api/v1/chat`·`/ws` → `:8092`)
```

설계 문서 2.2 "프론트" 행을 `Vite 프록시: /api/v1/auth → http://localhost (caddy 경유 board), /api/v1/chat → http://localhost:8092, /ws → ws://localhost:8092`로 정정. 5.1 heartbeat 행은 이미 10s/10s이므로 유지.

- [ ] **E2E (수동)**: chat-app을 compose로 띄우고(`.env`의 `JWT_SECRET`이 board 값이어야 함), `npm run dev`. 브라우저 두 개(일반·시크릿)에서 서로 다른 board 계정으로:
  1. 로그인 → 로비. 새로고침해도 로그인 유지(reissue 복원)
  2. A가 방 생성 → B의 로비에 즉시 나타남(ROOM_CREATED)
  3. B 입장 → A의 방 화면에 "…님이 입장했습니다", 멤버 목록의 B가 초록 점
  4. 대화 왕복, 빈 메시지는 버튼 비활성, 1000자 제한
  5. "이전 메시지 더 보기"로 50개 단위 이력
  6. B 탭 닫기 → A에 퇴장 메시지, B 회색 점
  7. board 사이트(`http://localhost`)에서 로그아웃 → chat 다음 전송에서 LOGIN_REQUIRED → 로그인 페이지로
  8. 서버 재시작 → 헤더 점이 노랑(connecting) → 초록으로 복귀, 구독 복구되어 메시지 수신
- [ ] `npm run lint && npm test && npm run build`, `./gradlew test` → 커밋 `feat: 라우팅 완성 + README 프론트 실행 안내 + 설계 문서 로컬 프록시 정정`

---

### Task 10: walkthrough

`docs/lecture/day3_react_client_walkthrough.md`. Task 0~9 순서 그대로. 각 절: 왜 지금, 전체 파일, `| 모듈/함수 | 출처 | 역할 |` 표(출처: React / react-router-dom / axios / @stomp/stompjs / Vite / Vitest / 1일차·2일차 서버 API / N절), 확인 명령. 첫 절에 "브라우저에서 토큰이 사는 곳"(메모리 vs 쿠키) 그림과 재연결 상태 다이어그램(mermaid stateDiagram) 포함. 함정 표: 프록시 대상 `http://localhost` vs `:8090`, 쿠키는 포트를 구분하지 않아 board 사이트 로그인이 chat dev에도 적용됨, StrictMode 이중 마운트로 activate/deactivate 두 번, Origin 403, `AxiosHeaders` 접근.

- [ ] 커밋 `docs: 3일차 React 클라이언트 walkthrough (코드 무변경)`

---

## Self-Review

- Spec 커버리지: 2.4 구조(파일명 일부 조정: `stompClient.js` → `chatSocket.js`, 훅 두 개 → `useSubscription` 하나로 통합 — 설계 문서 2.4를 Task 9에서 함께 정정), 5.1 heartbeat(Task 0), 5.2 프레임 전부, 7.4 프론트 규칙(401만 reissue, TOKEN_EXPIRED 재연결, LOGIN_REQUIRED 중단, 백오프 1s→30s, 선제 갱신 60초 전), 10.2 Vitest 대상 2개 + lint/build 게이트. 회원가입 없음(설계 2.4).
- 타입 일관성: `createChatSocket` 옵션·반환 형태가 Task 5·6·8에서 동일. `useAuth()` 반환(`user, booting, isAuthenticated, login, logout, refresh`)이 Task 4·6·7·8에서 동일. `history()` 응답 필드(`messages, hasMore, nextBefore`)가 2일차 `MessagePageResponse`와 일치.
- 미해결 결정(수업 중 선택): 선제 갱신 직후 즉시 재연결(`socket.reconnect()`) 여부 — 기본은 서버의 TOKEN_EXPIRED에 반응하는 지연 재연결.
