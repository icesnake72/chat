# 1일차 인증 연동 + STOMP CONNECT 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** board가 발급한 JWT를 chat 서버가 독립 검증하는 REST 인증(`GET /api/v1/chat/me`)과 STOMP CONNECT 인증 + echo 브로드캐스트를 완성한다.

**Architecture:** board의 `JwtTokenProvider`/`JwtAuthenticationFilter` 패턴을 검증 전용으로 이식하고, 같은 검증 로직(`BearerTokenAuthenticator`)을 HTTP 필터와 STOMP `ChannelInterceptor` 양쪽에서 재사용한다. 사용자 정보는 `JdbcClient`로 `board.users`를 읽고, 로그아웃 반영은 board-redis의 `deny:{jti}`를 읽어 처리한다. STOMP는 내장 simple broker를 쓴다.

**Tech Stack:** Spring Boot 4.1.1 (Spring Framework 7.0.9, Spring Security 7.1.1, Jackson 3), Java 21, jjwt 0.12.6, spring-boot-starter-websocket, spring-boot-starter-data-redis, JdbcClient, H2(테스트), JUnit 6 + Mockito + AssertJ

**Spec:** `docs/design/2026-09-12-stomp-chat-design.md` (섹션 2, 5, 6.2의 `/me`, 7, 10)

## Global Constraints

- `../board` 디렉토리는 읽기 전용. 파일 생성/수정 금지.
- 들여쓰기는 스페이스 2칸. 탭 금지 (initializr가 만든 `build.gradle`의 탭도 교체).
- 비밀값(JWT secret, DB 비밀번호)은 `src/main` 어디에도 기본값을 두지 않는다. `.env`(gitignore)와 환경변수로만 주입. `src/test` 아래 픽스처는 예외.
- Java 코드는 Google Java Style, Lombok 사용 가능. 주석은 최소화하되 강의용 "왜"는 한 줄 허용.
- 서버 포트 8092. board-app은 로컬에서 `http://localhost`(caddy 경유)로만 접근 가능(8090은 호스트에 publish 안 됨).
- Boot 4 패키지 주의: `@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure`, `ObjectMapper`는 `tools.jackson.databind.ObjectMapper`, JSON 메시지 컨버터는 `org.springframework.messaging.converter.JacksonJsonMessageConverter`.
- 커밋 메시지는 `feat:`/`test:`/`docs:` 접두어 + 한국어. 사용자 hook(`~/.githooks/secret-scan.py`)은 `src/test/`를 검사에서 제외한다.
- 각 Task 종료 시 `./gradlew test` green 확인 후 커밋.

---

## 파일 구조

| 파일 | 책임 |
|---|---|
| `build.gradle` | 의존성 (actuator 추가, 2-space) |
| `src/main/resources/application.yaml` | 포트 8092, DB/Redis/JWT/app 설정 (전부 환경변수 참조) |
| `src/main/java/com/example/chat/global/exception/*` | `ErrorCode`, `ErrorResponse`, `BusinessException` 계열, `GlobalExceptionHandler` |
| `src/main/java/com/example/chat/auth/JwtTokenProvider.java` | 검증 전용 파서 (`parse`, `isExpired`) |
| `src/main/java/com/example/chat/auth/TokenClaims.java` | 파싱 결과 record |
| `src/main/java/com/example/chat/auth/TokenDenylist.java`, `RedisTokenDenylist.java` | `deny:{jti}` 읽기 |
| `src/main/java/com/example/chat/auth/BoardUserReader.java`, `BoardUser.java` | board 스키마 읽기 |
| `src/main/java/com/example/chat/auth/ChatPrincipal.java` | Principal + Authentication 변환 |
| `src/main/java/com/example/chat/auth/BearerTokenAuthenticator.java` | 토큰 → ChatPrincipal (필터·인터셉터 공용) |
| `src/main/java/com/example/chat/auth/JwtAuthenticationFilter.java` | REST 인증 필터 |
| `src/main/java/com/example/chat/auth/MeController.java`, `dto/MeResponse.java` | `GET /api/v1/chat/me` |
| `src/main/java/com/example/chat/global/config/SecurityConfig.java`, `RestAuthenticationEntryPoint.java`, `RestAccessDeniedHandler.java` | 필터 체인, 401/403 JSON |
| `src/main/java/com/example/chat/auth/StompAuthException.java`, `StompAuthChannelInterceptor.java` | STOMP 인증 |
| `src/main/java/com/example/chat/global/config/WebSocketConfig.java`, `StompErrorHandler.java` | 엔드포인트·브로커·ERROR 프레임 |
| `src/main/java/com/example/chat/message/EchoController.java`, `dto/EchoRequest.java`, `dto/EchoResponse.java` | 1일차 echo (2일차에 메시지 도메인으로 대체) |
| `src/main/resources/static/index.html` | STOMP 수동 검증 콘솔 |
| `src/test/resources/application.yaml`, `schema-board.sql` | H2 + board 스키마 픽스처 |
| `src/test/java/com/example/chat/support/*` | `TestJwtFactory`, `InMemoryTokenDenylist`, `TestTokenStoreConfig` |
| `Dockerfile`, `docker-compose.yml`, `docker-compose.local.yml`, `scripts/*.sh`, `.env.example` | 로컬 도커 실행 (board-redis 접근용) |
| `docs/lecture/day1_auth_stomp_walkthrough.md` | 수업용 따라하기 문서 |

---

### Task 1: 빌드 기반 (M0)

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/resources/application.yaml`
- Create: `src/test/resources/schema-board.sql`
- Create: `src/test/java/com/example/chat/support/TestJwtFactory.java`
- Create: `.env.example`
- Create: `scripts/init_db.sh`

**Interfaces:**
- Produces: `TestJwtFactory.SECRET_BASE64`, `TestJwtFactory.token(String username)`, `token(String, Duration ttl)`, `tokenWithJti(String, String jti, Duration)`, `expiredToken(String)`, `tokenSignedWithOtherKey(String)`. 테스트 DB에 `board.users`의 `alice`(id 1, nickname 앨리스), `noprofile`(id 2, 프로필 없음) 시드.

- [ ] **Step 1: build.gradle을 2-space로 다시 쓰고 actuator를 추가**

```gradle
plugins {
  id 'java'
  id 'org.springframework.boot' version '4.1.1'
  id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

// @PreAuthorize SpEL의 #id 바인딩과 record 프로퍼티 바인딩에 필요 (board와 동일)
tasks.named('compileJava') {
  options.compilerArgs << '-parameters'
}

repositories {
  mavenCentral()
}

dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-webmvc'
  implementation 'org.springframework.boot:spring-boot-starter-websocket'
  implementation 'org.springframework.boot:spring-boot-starter-security'
  implementation 'org.springframework.boot:spring-boot-starter-validation'
  implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
  implementation 'org.springframework.boot:spring-boot-starter-data-redis'
  implementation 'org.springframework.boot:spring-boot-starter-actuator'

  implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
  runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
  runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'

  runtimeOnly 'com.mysql:mysql-connector-j'

  compileOnly 'org.projectlombok:lombok'
  annotationProcessor 'org.projectlombok:lombok'

  testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
  testImplementation 'org.springframework.boot:spring-boot-starter-websocket-test'
  testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
  testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
  testImplementation 'org.springframework.boot:spring-boot-starter-validation-test'
  testRuntimeOnly 'com.h2database:h2'
  testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
  testCompileOnly 'org.projectlombok:lombok'
  testAnnotationProcessor 'org.projectlombok:lombok'
}

tasks.named('test') {
  useJUnitPlatform()
}
```

- [ ] **Step 2: main application.yaml 작성**

```yaml
server:
  port: 8092
  # nginx/caddy 뒤에서 X-Forwarded-*로 원래 scheme/host를 복원 (WebSocket origin 검사에 필요)
  forward-headers-strategy: framework

spring:
  application:
    name: chat
  config:
    import: optional:file:.env[.properties]
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:chat}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

jwt:
  # board와 동일한 Base64 secret. 기본값 없음 — .env 또는 환경변수 필수
  secret: ${JWT_SECRET}

app:
  board:
    schema: ${APP_BOARD_SCHEMA:board}
  ws:
    allowed-origin-patterns: ${APP_WS_ALLOWED_ORIGINS:http://localhost:*}

management:
  endpoints:
    web:
      exposure:
        include: health

logging:
  level:
    com.example.chat: debug
```

- [ ] **Step 3: test application.yaml 작성** (`src/test/resources/application.yaml`)

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:chat;MODE=MySQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  sql:
    init:
      mode: always
      schema-locations: classpath:schema-board.sql
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
  data:
    redis:
      host: localhost
      port: 6379

jwt:
  secret: dGVzdC1zZWNyZXQtZm9yLWNoYXQtdW5pdC10ZXN0cy1vbmx5LTEyMzQ1Njc4OTA=

app:
  board:
    schema: board
  ws:
    allowed-origin-patterns: "*"

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 4: schema-board.sql 작성** (`src/test/resources/schema-board.sql`)

```sql
-- board 프로젝트의 users / user_profiles 를 H2에 흉내낸 픽스처 (읽기 전용 조회 검증용)
CREATE SCHEMA IF NOT EXISTS board;

CREATE TABLE IF NOT EXISTS board.users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  email VARCHAR(100) NOT NULL UNIQUE,
  password VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  provider_id VARCHAR(100),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS board.user_profiles (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL UNIQUE,
  nickname VARCHAR(50) NOT NULL UNIQUE,
  bio VARCHAR(500),
  phone_number VARCHAR(20),
  birth_date DATE,
  profile_image_url VARCHAR(500),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

MERGE INTO board.users (id, username, email, password, role, provider, created_at, updated_at)
  KEY (id) VALUES
  (1, 'alice', 'alice@example.com', 'x', 'USER', 'LOCAL', NOW(), NOW()),
  (2, 'noprofile', 'noprofile@example.com', 'x', 'USER', 'LOCAL', NOW(), NOW());

MERGE INTO board.user_profiles (id, user_id, nickname, created_at, updated_at)
  KEY (id) VALUES
  (1, 1, '앨리스', NOW(), NOW());
```

- [ ] **Step 5: TestJwtFactory 작성** (`src/test/java/com/example/chat/support/TestJwtFactory.java`)

```java
package com.example.chat.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

// board의 JwtTokenProvider.createToken과 같은 claims(sub, jti, iat, exp)로 테스트 토큰을 만든다.
public final class TestJwtFactory {

  public static final String SECRET_BASE64 =
      "dGVzdC1zZWNyZXQtZm9yLWNoYXQtdW5pdC10ZXN0cy1vbmx5LTEyMzQ1Njc4OTA=";
  private static final String OTHER_SECRET_BASE64 =
      "b3RoZXItc2VjcmV0LWZvci1jaGF0LXVuaXQtdGVzdHMtb25seS0wOTg3NjU0MzIx";

  private static final SecretKey KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET_BASE64));
  private static final SecretKey OTHER_KEY =
      Keys.hmacShaKeyFor(Decoders.BASE64.decode(OTHER_SECRET_BASE64));

  private TestJwtFactory() {
  }

  public static String token(String username) {
    return token(username, Duration.ofHours(1));
  }

  public static String token(String username, Duration ttl) {
    return tokenWithJti(username, UUID.randomUUID().toString(), ttl);
  }

  public static String tokenWithJti(String username, String jti, Duration ttl) {
    return build(KEY, username, jti, ttl);
  }

  public static String expiredToken(String username) {
    return token(username, Duration.ofMinutes(-1));
  }

  public static String tokenSignedWithOtherKey(String username) {
    return build(OTHER_KEY, username, UUID.randomUUID().toString(), Duration.ofHours(1));
  }

  private static String build(SecretKey key, String username, String jti, Duration ttl) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(username)
        .id(jti)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(key)
        .compact();
  }
}
```

- [ ] **Step 6: .env.example와 scripts/init_db.sh 작성**

`.env.example`:

```bash
# cp .env.example .env 후 값을 채운다. application.yaml의 spring.config.import가 이 파일을 읽는다.
# docker compose도 env_file로 같은 파일을 컨테이너에 주입한다.

# board와 동일한 Base64 secret (board/src/main/resources/application.yaml의 jwt.secret 값을 복사)
JWT_SECRET=

# MySQL (compose 실행 시 DB_HOST는 mysql-8 로 자동 설정된다)
DB_HOST=localhost
DB_PORT=3306
DB_NAME=chat
DB_USERNAME=root
DB_PASSWORD=

# Redis — board-redis는 호스트 포트를 열지 않는다.
#   docker compose 실행: compose가 REDIS_HOST=board-redis 로 덮어쓴다.
#   ./gradlew bootRun 실행: scripts/dev_redis_proxy.sh 로 127.0.0.1:6380 → board-redis 터널을 열고 아래 값 사용
REDIS_HOST=localhost
REDIS_PORT=6380

# WebSocket handshake Origin 허용 패턴 (운영: https://chat.alldayai.org)
# APP_WS_ALLOWED_ORIGINS=http://localhost:*
```

`scripts/init_db.sh`:

```bash
#!/usr/bin/env bash
# chat 데이터베이스를 mysql-8 컨테이너에 만든다 (없을 때만). board DB는 건드리지 않는다.
set -euo pipefail

: "${DB_USERNAME:=root}"
: "${DB_PASSWORD:?DB_PASSWORD 환경변수가 필요합니다 (.env 참고)}"
: "${DB_NAME:=chat}"

docker exec mysql-8 mysql -u"${DB_USERNAME}" -p"${DB_PASSWORD}" \
  -e "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "database '${DB_NAME}' ready"
```

- [ ] **Step 7: 테스트 실행**

Run: `./gradlew --no-daemon test`
Expected: BUILD SUCCESSFUL, `ChatApplicationTests.contextLoads` PASS (H2 + schema-board.sql 로드 확인은 `build/test-results/test/*.xml`에 failures=0)

- [ ] **Step 8: 커밋**

```bash
chmod +x scripts/init_db.sh
git add build.gradle src/main/resources/application.yaml src/test scripts .env.example
git commit -m "feat: 빌드 기반 — actuator 추가, 환경변수 기반 설정, H2 board 스키마 픽스처, 테스트 JWT 팩토리"
```

---

### Task 2: 예외 계층 (board 규약 이식)

**Files:**
- Create: `src/main/java/com/example/chat/global/exception/ErrorCode.java`
- Create: `src/main/java/com/example/chat/global/exception/ErrorResponse.java`
- Create: `src/main/java/com/example/chat/global/exception/BusinessException.java`
- Create: `src/main/java/com/example/chat/global/exception/NotFoundException.java`
- Create: `src/main/java/com/example/chat/global/exception/DuplicateException.java`
- Create: `src/main/java/com/example/chat/global/exception/UnauthorizedException.java`
- Create: `src/main/java/com/example/chat/global/exception/ForbiddenException.java`
- Create: `src/main/java/com/example/chat/global/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/example/chat/global/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `ErrorCode` enum (`getStatus()`, `getMessage()`), `ErrorResponse.of(ErrorCode)`, `ErrorResponse.of(ErrorCode, List<FieldErrorDetail>)`, `BusinessException(ErrorCode)`와 하위 4종.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.example.chat.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void businessExceptionUsesErrorCodeStatusAndBody() {
    ResponseEntity<ErrorResponse> response =
        handler.handleBusinessException(new UnauthorizedException(ErrorCode.LOGIN_REQUIRED));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().code()).isEqualTo("LOGIN_REQUIRED");
    assertThat(response.getBody().message()).isEqualTo(ErrorCode.LOGIN_REQUIRED.getMessage());
    assertThat(response.getBody().timestamp()).isNotNull();
    assertThat(response.getBody().errors()).isNull();
  }

  @Test
  void accessDeniedBecomes403() {
    ResponseEntity<ErrorResponse> response =
        handler.handleAccessDenied(new AccessDeniedException("denied"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().code()).isEqualTo("ACCESS_DENIED");
  }

  @Test
  void unexpectedExceptionHidesDetails() {
    ResponseEntity<ErrorResponse> response =
        handler.handleException(new IllegalStateException("db down: secret detail"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().message()).doesNotContain("secret detail");
  }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.global.exception.*'`
Expected: FAIL (compilation error: `GlobalExceptionHandler`, `ErrorCode` 없음)

- [ ] **Step 3: 구현**

`ErrorCode.java`:

```java
package com.example.chat.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// HTTP 상태의 단일 권위. 하위 예외 클래스는 라벨일 뿐 상태는 여기서만 정한다 (board와 동일).
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "access token이 만료되었습니다. 재발급 후 다시 연결하세요."),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문(JSON)을 읽을 수 없습니다."),

  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String message;
}
```

`ErrorResponse.java` (`errors`가 null이면 응답에서 빠지도록 `@JsonInclude` — Jackson 3에서도 애노테이션 패키지는 `com.fasterxml.jackson.annotation` 그대로다):

```java
package com.example.chat.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String code,
    String message,
    LocalDateTime timestamp,
    List<FieldErrorDetail> errors
) {

  public record FieldErrorDetail(String field, String reason) {
  }

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now(), null);
  }

  public static ErrorResponse of(ErrorCode errorCode, List<FieldErrorDetail> errors) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now(), errors);
  }
}
```

`BusinessException.java`와 하위 4종:

```java
package com.example.chat.global.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
```

```java
package com.example.chat.global.exception;

public class NotFoundException extends BusinessException {

  public NotFoundException(ErrorCode errorCode) {
    super(errorCode);
  }
}
```

```java
package com.example.chat.global.exception;

public class DuplicateException extends BusinessException {

  public DuplicateException(ErrorCode errorCode) {
    super(errorCode);
  }
}
```

```java
package com.example.chat.global.exception;

public class UnauthorizedException extends BusinessException {

  public UnauthorizedException(ErrorCode errorCode) {
    super(errorCode);
  }
}
```

```java
package com.example.chat.global.exception;

public class ForbiddenException extends BusinessException {

  public ForbiddenException(ErrorCode errorCode) {
    super(errorCode);
  }
}
```

`GlobalExceptionHandler.java`:

```java
package com.example.chat.global.exception;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// REST 경로의 예외를 ErrorResponse로 바꾸는 한 곳. STOMP 경로는 여기를 거치지 않는다 (StompErrorHandler 참고).
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    log.warn("BusinessException: code={}, message={}", errorCode.name(), e.getMessage());
    return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
    List<ErrorResponse.FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
        .map(error -> new ErrorResponse.FieldErrorDetail(error.getField(), error.getDefaultMessage()))
        .toList();
    log.warn("Validation failed: {}", errors);
    return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
        .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, errors));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
    log.warn("Malformed request: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.getStatus())
        .body(ErrorResponse.of(ErrorCode.MALFORMED_REQUEST));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
    log.warn("Access denied: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.ACCESS_DENIED.getStatus())
        .body(ErrorResponse.of(ErrorCode.ACCESS_DENIED));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
    return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
        .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    log.error("Unexpected exception", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
        .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.global.exception.*'`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/chat/global/exception src/test/java/com/example/chat/global/exception
git commit -m "feat: 예외 계층 — ErrorCode 단일 권위 + ErrorResponse + GlobalExceptionHandler (board 규약 이식)"
```

---

### Task 3: JwtTokenProvider (검증 전용)

**Files:**
- Create: `src/main/java/com/example/chat/auth/TokenClaims.java`
- Create: `src/main/java/com/example/chat/auth/JwtTokenProvider.java`
- Test: `src/test/java/com/example/chat/auth/JwtTokenProviderTest.java`

**Interfaces:**
- Produces: `record TokenClaims(String username, String jti, Instant expiresAt)`, `Optional<TokenClaims> JwtTokenProvider.parse(String token)`, `boolean JwtTokenProvider.isExpired(String token)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.example.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chat.support.TestJwtFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

  private final JwtTokenProvider provider = new JwtTokenProvider(TestJwtFactory.SECRET_BASE64);

  @Test
  void parsesValidToken() {
    String token = TestJwtFactory.tokenWithJti("alice", "jti-1", Duration.ofMinutes(10));

    Optional<TokenClaims> claims = provider.parse(token);

    assertThat(claims).isPresent();
    assertThat(claims.get().username()).isEqualTo("alice");
    assertThat(claims.get().jti()).isEqualTo("jti-1");
    assertThat(claims.get().expiresAt()).isAfter(Instant.now());
  }

  @Test
  void rejectsExpiredToken() {
    String token = TestJwtFactory.expiredToken("alice");

    assertThat(provider.parse(token)).isEmpty();
    assertThat(provider.isExpired(token)).isTrue();
  }

  @Test
  void rejectsTokenSignedWithOtherKey() {
    String token = TestJwtFactory.tokenSignedWithOtherKey("alice");

    assertThat(provider.parse(token)).isEmpty();
    assertThat(provider.isExpired(token)).isFalse();
  }

  @Test
  void rejectsGarbageAndBlank() {
    assertThat(provider.parse("not.a.jwt")).isEmpty();
    assertThat(provider.parse("")).isEmpty();
    assertThat(provider.parse(null)).isEmpty();
    assertThat(provider.isExpired(null)).isFalse();
  }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.JwtTokenProviderTest'`
Expected: FAIL (`JwtTokenProvider` 없음)

- [ ] **Step 3: 구현**

`TokenClaims.java`:

```java
package com.example.chat.auth;

import java.time.Instant;

// board 토큰의 claims 중 chat이 쓰는 것만: sub(username), jti(denylist 키), exp
public record TokenClaims(String username, String jti, Instant expiresAt) {
}
```

`JwtTokenProvider.java`:

```java
package com.example.chat.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Optional;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// board의 JwtTokenProvider에서 "검증" 부분만 이식했다. 발급(createToken)은 board의 책임이라 없다.
// 같은 Base64 secret을 공유하면 서버 간 호출 없이 서명을 검증할 수 있다.
@Slf4j
@Component
public class JwtTokenProvider {

  private final SecretKey key;

  public JwtTokenProvider(@Value("${jwt.secret}") String base64Secret) {
    this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
  }

  // 서명·만료·형식이 모두 유효할 때만 claims를 돌려준다. jti가 없는 토큰은 denylist를 적용할 수 없어 무효로 본다.
  public Optional<TokenClaims> parse(String token) {
    try {
      Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
      if (claims.getId() == null || claims.getSubject() == null || claims.getExpiration() == null) {
        log.debug("jwt missing required claims");
        return Optional.empty();
      }
      return Optional.of(new TokenClaims(
          claims.getSubject(), claims.getId(), claims.getExpiration().toInstant()));
    } catch (JwtException | IllegalArgumentException e) {
      log.debug("invalid jwt: {}", e.getMessage());
      return Optional.empty();
    }
  }

  // "만료라서 실패했는가"만 답한다 — 클라이언트에 TOKEN_EXPIRED(재발급 유도)와 LOGIN_REQUIRED를 구분해 주기 위함.
  public boolean isExpired(String token) {
    try {
      Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
      return false;
    } catch (ExpiredJwtException e) {
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.JwtTokenProviderTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/chat/auth/TokenClaims.java src/main/java/com/example/chat/auth/JwtTokenProvider.java src/test/java/com/example/chat/auth/JwtTokenProviderTest.java
git commit -m "feat: JwtTokenProvider — board 토큰 검증 전용 이식 (parse, isExpired)"
```

---

### Task 4: TokenDenylist (Redis 읽기 + 테스트용 InMemory)

**Files:**
- Create: `src/main/java/com/example/chat/auth/TokenDenylist.java`
- Create: `src/main/java/com/example/chat/auth/RedisTokenDenylist.java`
- Create: `src/test/java/com/example/chat/support/InMemoryTokenDenylist.java`
- Create: `src/test/java/com/example/chat/support/TestTokenStoreConfig.java`
- Test: `src/test/java/com/example/chat/auth/RedisTokenDenylistTest.java`

**Interfaces:**
- Produces: `boolean TokenDenylist.isDenied(String jti)`, 테스트용 `InMemoryTokenDenylist.deny(String jti)`, `clear()`. `@SpringBootTest` 컨텍스트에서는 `TestTokenStoreConfig`가 InMemory 구현을 `@Primary`로 주입.

- [ ] **Step 1: 실패하는 테스트 작성** (Redis 구현은 `StringRedisTemplate`을 mock)

```java
package com.example.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisTokenDenylistTest {

  @Mock StringRedisTemplate redis;

  @Test
  void deniedWhenKeyExists() {
    given(redis.hasKey("deny:jti-1")).willReturn(true);

    assertThat(new RedisTokenDenylist(redis).isDenied("jti-1")).isTrue();
  }

  @Test
  void notDeniedWhenKeyMissing() {
    given(redis.hasKey("deny:jti-2")).willReturn(false);

    assertThat(new RedisTokenDenylist(redis).isDenied("jti-2")).isFalse();
  }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.RedisTokenDenylistTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

`TokenDenylist.java`:

```java
package com.example.chat.auth;

// board가 로그아웃 시 등록하는 deny:{jti} 를 읽기만 한다. chat은 이 키를 쓰지 않는다.
public interface TokenDenylist {

  boolean isDenied(String jti);
}
```

`RedisTokenDenylist.java`:

```java
package com.example.chat.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// board의 RedisTokenDenylist와 같은 키 스키마(deny:{jti}). Redis 장애는 예외를 삼키지 않는다 — fail-closed.
@Component
@RequiredArgsConstructor
public class RedisTokenDenylist implements TokenDenylist {

  private static final String KEY_PREFIX = "deny:";

  private final StringRedisTemplate redis;

  @Override
  public boolean isDenied(String jti) {
    return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
  }
}
```

`InMemoryTokenDenylist.java` (`src/test/java/com/example/chat/support/`):

```java
package com.example.chat.support;

import com.example.chat.auth.TokenDenylist;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Redis 없이 membership만 흉내낸다. @Transactional 롤백이 되돌리지 못하므로 @BeforeEach clear() 필요.
public class InMemoryTokenDenylist implements TokenDenylist {

  private final Set<String> denied = ConcurrentHashMap.newKeySet();

  public void deny(String jti) {
    denied.add(jti);
  }

  public void clear() {
    denied.clear();
  }

  @Override
  public boolean isDenied(String jti) {
    return denied.contains(jti);
  }
}
```

`TestTokenStoreConfig.java` (`src/test/java/com/example/chat/support/`):

```java
package com.example.chat.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// 테스트 클래스패스의 @Configuration은 ChatApplication 컴포넌트 스캔 범위(com.example.chat)라 자동 적용된다.
@Configuration
public class TestTokenStoreConfig {

  @Bean
  @Primary
  public InMemoryTokenDenylist inMemoryTokenDenylist() {
    return new InMemoryTokenDenylist();
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test`
Expected: PASS 전체 (contextLoads도 InMemory 주입으로 통과)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/chat/auth/TokenDenylist.java src/main/java/com/example/chat/auth/RedisTokenDenylist.java src/test/java/com/example/chat/support src/test/java/com/example/chat/auth/RedisTokenDenylistTest.java
git commit -m "feat: TokenDenylist — board-redis deny:{jti} 읽기 전용 + 테스트용 InMemory 대체"
```

---

### Task 5: BoardUserReader (board 스키마 읽기)

**Files:**
- Create: `src/main/java/com/example/chat/auth/BoardUser.java`
- Create: `src/main/java/com/example/chat/auth/BoardUserReader.java`
- Test: `src/test/java/com/example/chat/auth/BoardUserReaderTest.java`

**Interfaces:**
- Produces: `record BoardUser(Long id, String username, String nickname)`, `Optional<BoardUser> BoardUserReader.findByUsername(String username)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.example.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class BoardUserReaderTest {

  @Autowired BoardUserReader reader;
  @Autowired JdbcClient jdbcClient;

  @Test
  void findsUserWithProfile() {
    Optional<BoardUser> user = reader.findByUsername("alice");

    assertThat(user).isPresent();
    assertThat(user.get().id()).isEqualTo(1L);
    assertThat(user.get().nickname()).isEqualTo("앨리스");
  }

  @Test
  void emptyWhenUnknownUsername() {
    assertThat(reader.findByUsername("nobody")).isEmpty();
  }

  @Test
  void emptyWhenProfileMissing() {
    assertThat(reader.findByUsername("noprofile")).isEmpty();
  }

  @Test
  void rejectsUnsafeSchemaName() {
    assertThatThrownBy(() -> new BoardUserReader(jdbcClient, "board; DROP TABLE x"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.BoardUserReaderTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

`BoardUser.java`:

```java
package com.example.chat.auth;

public record BoardUser(Long id, String username, String nickname) {
}
```

`BoardUserReader.java`:

```java
package com.example.chat.auth;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// 토큰에는 username뿐이라 userId·nickname은 board 스키마에서 읽는다. JPA 엔티티로 매핑하지 않는 이유:
// board의 ddl-auto가 관리하는 테이블을 chat의 Hibernate가 검증·변경하지 않게 하기 위함 (읽기 전용 경계).
@Component
public class BoardUserReader {

  private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");

  private final JdbcClient jdbcClient;
  private final String sql;

  public BoardUserReader(JdbcClient jdbcClient, @Value("${app.board.schema}") String schema) {
    if (!SAFE_SCHEMA.matcher(schema).matches()) {
      throw new IllegalArgumentException("invalid board schema name: " + schema);
    }
    this.jdbcClient = jdbcClient;
    this.sql = """
        SELECT u.id, u.username, p.nickname
        FROM %1$s.users u
        JOIN %1$s.user_profiles p ON p.user_id = u.id
        WHERE u.username = :username
        """.formatted(schema);
  }

  public Optional<BoardUser> findByUsername(String username) {
    return jdbcClient.sql(sql)
        .param("username", username)
        .query((rs, rowNum) -> new BoardUser(
            rs.getLong("id"), rs.getString("username"), rs.getString("nickname")))
        .optional();
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.BoardUserReaderTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/chat/auth/BoardUser.java src/main/java/com/example/chat/auth/BoardUserReader.java src/test/java/com/example/chat/auth/BoardUserReaderTest.java
git commit -m "feat: BoardUserReader — JdbcClient로 board.users/user_profiles 읽기 전용 조회"
```

---

### Task 6: ChatPrincipal + BearerTokenAuthenticator

**Files:**
- Create: `src/main/java/com/example/chat/auth/ChatPrincipal.java`
- Create: `src/main/java/com/example/chat/auth/BearerTokenAuthenticator.java`
- Test: `src/test/java/com/example/chat/auth/ChatPrincipalTest.java`
- Test: `src/test/java/com/example/chat/auth/BearerTokenAuthenticatorTest.java`

**Interfaces:**
- Consumes: `JwtTokenProvider.parse`, `TokenDenylist.isDenied`, `BoardUserReader.findByUsername`
- Produces:
  - `record ChatPrincipal(Long userId, String username, String nickname, String jti, Instant expiresAt) implements Principal` — `getName()`은 username, `boolean isExpired(Instant now)`, `Authentication toAuthentication()`, `static Optional<ChatPrincipal> from(Principal user)`
  - `BearerTokenAuthenticator`: `static Optional<String> extractToken(String authorizationHeader)`, `Optional<ChatPrincipal> authenticate(String token)`

- [ ] **Step 1: 실패하는 테스트 작성**

`ChatPrincipalTest.java`:

```java
package com.example.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class ChatPrincipalTest {

  private final ChatPrincipal principal =
      new ChatPrincipal(1L, "alice", "앨리스", "jti-1", Instant.parse("2030-01-01T00:00:00Z"));

  @Test
  void nameIsUsername() {
    assertThat(principal.getName()).isEqualTo("alice");
  }

  @Test
  void expiryComparesAgainstGivenInstant() {
    assertThat(principal.isExpired(Instant.parse("2029-12-31T23:59:59Z"))).isFalse();
    assertThat(principal.isExpired(Instant.parse("2030-01-01T00:00:00Z"))).isTrue();
  }

  @Test
  void roundTripsThroughAuthentication() {
    Authentication auth = principal.toAuthentication();

    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getName()).isEqualTo("alice");
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    assertThat(ChatPrincipal.from(auth)).contains(principal);
    assertThat(ChatPrincipal.from(null)).isEmpty();
    assertThat(ChatPrincipal.from(() -> "other")).isEmpty();
  }
}
```

`BearerTokenAuthenticatorTest.java`:

```java
package com.example.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BearerTokenAuthenticatorTest {

  @Mock JwtTokenProvider tokenProvider;
  @Mock TokenDenylist tokenDenylist;
  @Mock BoardUserReader boardUserReader;
  @InjectMocks BearerTokenAuthenticator authenticator;

  private final TokenClaims claims =
      new TokenClaims("alice", "jti-1", Instant.parse("2030-01-01T00:00:00Z"));

  @Test
  void extractsBearerToken() {
    assertThat(BearerTokenAuthenticator.extractToken("Bearer abc")).contains("abc");
    assertThat(BearerTokenAuthenticator.extractToken("Basic abc")).isEmpty();
    assertThat(BearerTokenAuthenticator.extractToken(null)).isEmpty();
    assertThat(BearerTokenAuthenticator.extractToken("")).isEmpty();
  }

  @Test
  void authenticatesValidTokenOfKnownUser() {
    given(tokenProvider.parse("t")).willReturn(Optional.of(claims));
    given(tokenDenylist.isDenied("jti-1")).willReturn(false);
    given(boardUserReader.findByUsername("alice"))
        .willReturn(Optional.of(new BoardUser(1L, "alice", "앨리스")));

    Optional<ChatPrincipal> principal = authenticator.authenticate("t");

    assertThat(principal).contains(
        new ChatPrincipal(1L, "alice", "앨리스", "jti-1", claims.expiresAt()));
  }

  @Test
  void emptyWhenTokenInvalid() {
    given(tokenProvider.parse("bad")).willReturn(Optional.empty());

    assertThat(authenticator.authenticate("bad")).isEmpty();
    verify(tokenDenylist, never()).isDenied(anyString());
    verify(boardUserReader, never()).findByUsername(anyString());
  }

  @Test
  void emptyWhenDenied() {
    given(tokenProvider.parse("t")).willReturn(Optional.of(claims));
    given(tokenDenylist.isDenied("jti-1")).willReturn(true);

    assertThat(authenticator.authenticate("t")).isEmpty();
    verify(boardUserReader, never()).findByUsername(anyString());
  }

  @Test
  void emptyWhenUserMissing() {
    given(tokenProvider.parse("t")).willReturn(Optional.of(claims));
    given(tokenDenylist.isDenied("jti-1")).willReturn(false);
    given(boardUserReader.findByUsername("alice")).willReturn(Optional.empty());

    assertThat(authenticator.authenticate("t")).isEmpty();
  }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.ChatPrincipalTest' --tests 'com.example.chat.auth.BearerTokenAuthenticatorTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

`ChatPrincipal.java`:

```java
package com.example.chat.auth;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

// REST(@AuthenticationPrincipal)와 STOMP(accessor.getUser()) 양쪽에서 같은 타입으로 사용자를 본다.
// jti·expiresAt을 함께 들고 있어 장수명 WebSocket 세션에서 프레임마다 재검사할 수 있다.
public record ChatPrincipal(
    Long userId,
    String username,
    String nickname,
    String jti,
    Instant expiresAt
) implements Principal {

  @Override
  public String getName() {
    return username;
  }

  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public Authentication toAuthentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        this, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }

  // STOMP 세션의 Principal(=Authentication)에서 ChatPrincipal을 꺼낸다
  public static Optional<ChatPrincipal> from(Principal user) {
    if (user instanceof Authentication authentication
        && authentication.getPrincipal() instanceof ChatPrincipal principal) {
      return Optional.of(principal);
    }
    return Optional.empty();
  }
}
```

`BearerTokenAuthenticator.java`:

```java
package com.example.chat.auth;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// "토큰 문자열 → 사용자" 한 단계를 HTTP 필터와 STOMP 인터셉터가 공유한다.
// 인증 실패(서명·만료·denylist·사용자 없음)는 empty, 인프라 오류(Redis·DB)는 예외로 전파한다 (fail-closed).
@Slf4j
@Component
@RequiredArgsConstructor
public class BearerTokenAuthenticator {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenProvider tokenProvider;
  private final TokenDenylist tokenDenylist;
  private final BoardUserReader boardUserReader;

  public static Optional<String> extractToken(String authorizationHeader) {
    if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith(BEARER_PREFIX)) {
      return Optional.of(authorizationHeader.substring(BEARER_PREFIX.length()));
    }
    return Optional.empty();
  }

  public Optional<ChatPrincipal> authenticate(String token) {
    Optional<TokenClaims> parsed = tokenProvider.parse(token);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    TokenClaims claims = parsed.get();
    if (tokenDenylist.isDenied(claims.jti())) {
      log.debug("denied jti: {}", claims.jti());
      return Optional.empty();
    }
    return boardUserReader.findByUsername(claims.username())
        .map(user -> new ChatPrincipal(
            user.id(), user.username(), user.nickname(), claims.jti(), claims.expiresAt()));
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.ChatPrincipalTest' --tests 'com.example.chat.auth.BearerTokenAuthenticatorTest'`
Expected: PASS (8 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/chat/auth/ChatPrincipal.java src/main/java/com/example/chat/auth/BearerTokenAuthenticator.java src/test/java/com/example/chat/auth/ChatPrincipalTest.java src/test/java/com/example/chat/auth/BearerTokenAuthenticatorTest.java
git commit -m "feat: ChatPrincipal + BearerTokenAuthenticator — 토큰→사용자 변환을 필터/인터셉터 공용으로"
```

---

### Task 7: REST 보안 체인 + `GET /api/v1/chat/me`

**Files:**
- Create: `src/main/java/com/example/chat/global/config/RestAuthenticationEntryPoint.java`
- Create: `src/main/java/com/example/chat/global/config/RestAccessDeniedHandler.java`
- Create: `src/main/java/com/example/chat/auth/JwtAuthenticationFilter.java`
- Create: `src/main/java/com/example/chat/global/config/SecurityConfig.java`
- Create: `src/main/java/com/example/chat/auth/dto/MeResponse.java`
- Create: `src/main/java/com/example/chat/auth/MeController.java`
- Test: `src/test/java/com/example/chat/auth/MeControllerTest.java`

**Interfaces:**
- Consumes: `BearerTokenAuthenticator`, `ChatPrincipal.toAuthentication()`, `ErrorResponse`, `InMemoryTokenDenylist`, `TestJwtFactory`
- Produces: `GET /api/v1/chat/me` → `MeResponse {userId, username, nickname}`; permitAll 경로 `/ws/**`, `/actuator/health`, `GET /`, `GET /index.html`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.example.chat.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.chat.support.InMemoryTokenDenylist;
import com.example.chat.support.TestJwtFactory;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MeControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired InMemoryTokenDenylist denylist;

  @BeforeEach
  void clearDenylist() {
    denylist.clear();
  }

  @Test
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/chat/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void returnsMeWithValidToken() throws Exception {
    mockMvc.perform(get("/api/v1/chat/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtFactory.token("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(1))
        .andExpect(jsonPath("$.username").value("alice"))
        .andExpect(jsonPath("$.nickname").value("앨리스"));
  }

  @Test
  void returns401WhenTokenExpired() throws Exception {
    mockMvc.perform(get("/api/v1/chat/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtFactory.expiredToken("alice")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void returns401WhenTokenDenied() throws Exception {
    String token = TestJwtFactory.tokenWithJti("alice", "jti-logout", Duration.ofHours(1));
    denylist.deny("jti-logout");

    mockMvc.perform(get("/api/v1/chat/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void returns401WhenUserUnknownInBoard() throws Exception {
    mockMvc.perform(get("/api/v1/chat/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtFactory.token("ghost")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void healthIsPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.MeControllerTest'`
Expected: FAIL (컴파일 오류 또는 404/403)

- [ ] **Step 3: 구현**

`RestAuthenticationEntryPoint.java`:

```java
package com.example.chat.global.config;

import com.example.chat.global.exception.ErrorCode;
import com.example.chat.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// 미인증 접근(401)을 ErrorResponse JSON으로. 필터 단계라 @RestControllerAdvice가 닿지 않는다.
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException) throws IOException {
    ErrorCode errorCode = ErrorCode.LOGIN_REQUIRED;
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }
}
```

`RestAccessDeniedHandler.java`:

```java
package com.example.chat.global.config;

import com.example.chat.global.exception.ErrorCode;
import com.example.chat.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException) throws IOException {
    ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }
}
```

`JwtAuthenticationFilter.java`:

```java
package com.example.chat.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

// board의 JwtAuthenticationFilter와 같은 원칙: 인증 정보를 "심기"만 하고 막지 않는다.
// 토큰이 없거나 무효면 컨텍스트를 비운 채 통과 → AuthorizationFilter가 401(entryPoint)로 응답한다.
// 내부 오류(Redis·DB)는 401로 둔갑시키지 않고 HandlerExceptionResolver로 위임해 500이 되게 한다.
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final BearerTokenAuthenticator authenticator;
  private final HandlerExceptionResolver handlerExceptionResolver;

  public JwtAuthenticationFilter(
      BearerTokenAuthenticator authenticator,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
    this.authenticator = authenticator;
    this.handlerExceptionResolver = handlerExceptionResolver;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    try {
      Optional<String> token =
          BearerTokenAuthenticator.extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
      if (token.isPresent()) {
        authenticator.authenticate(token.get()).ifPresent(principal -> {
          UsernamePasswordAuthenticationToken authentication =
              (UsernamePasswordAuthenticationToken) principal.toAuthentication();
          authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(authentication);
        });
      }
    } catch (Exception e) {
      SecurityContextHolder.clearContext();
      handlerExceptionResolver.resolveException(request, response, null, e);
      return;
    }
    filterChain.doFilter(request, response);
  }
}
```

`SecurityConfig.java`:

```java
package com.example.chat.global.config;

import com.example.chat.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// STATELESS + Bearer. 로그인/회원가입은 board의 책임이라 여기엔 없다.
// /ws/** 핸드셰이크는 공개 — 인증은 STOMP CONNECT 프레임에서 한다 (refresh 쿠키 path 제한 때문에 헤더로 전달).
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/ws/**").permitAll()
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers(HttpMethod.GET, "/", "/index.html").permitAll()
            .anyRequest().authenticated())
        .exceptionHandling(e -> e
            .authenticationEntryPoint(authenticationEntryPoint)
            .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
```

`MeResponse.java` (`auth/dto/`):

```java
package com.example.chat.auth.dto;

import com.example.chat.auth.ChatPrincipal;

public record MeResponse(Long userId, String username, String nickname) {

  public static MeResponse from(ChatPrincipal principal) {
    return new MeResponse(principal.userId(), principal.username(), principal.nickname());
  }
}
```

`MeController.java`:

```java
package com.example.chat.auth;

import com.example.chat.auth.dto.MeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class MeController {

  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal ChatPrincipal principal) {
    return MeResponse.from(principal);
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test`
Expected: PASS 전체 (MeControllerTest 6개 포함)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/chat src/test/java/com/example/chat/auth/MeControllerTest.java
git commit -m "feat: REST 인증 — JwtAuthenticationFilter + SecurityConfig + 401/403 JSON + GET /api/v1/chat/me"
```

---

### Task 8: StompAuthChannelInterceptor (CONNECT 인증 + 프레임 재검사)

**Files:**
- Create: `src/main/java/com/example/chat/auth/StompAuthException.java`
- Create: `src/main/java/com/example/chat/auth/StompAuthChannelInterceptor.java`
- Test: `src/test/java/com/example/chat/auth/StompAuthChannelInterceptorTest.java`

**Interfaces:**
- Consumes: `BearerTokenAuthenticator.authenticate/extractToken`, `JwtTokenProvider.isExpired`, `TokenDenylist.isDenied`, `ChatPrincipal.from/toAuthentication/isExpired`
- Produces: `StompAuthException(ErrorCode)` (`getErrorCode()`), `StompAuthChannelInterceptor implements ChannelInterceptor` (CONNECT: 인증 후 `accessor.setUser`; SEND/SUBSCRIBE: 만료·denylist 재검사)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.example.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.example.chat.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

  @Mock BearerTokenAuthenticator authenticator;
  @Mock JwtTokenProvider tokenProvider;
  @Mock TokenDenylist tokenDenylist;
  @Mock MessageChannel channel;
  @InjectMocks StompAuthChannelInterceptor interceptor;

  private static ChatPrincipal principal(Instant expiresAt) {
    return new ChatPrincipal(1L, "alice", "앨리스", "jti-1", expiresAt);
  }

  private static StompHeaderAccessor accessor(StompCommand command) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setLeaveMutable(true);
    return accessor;
  }

  private static Message<byte[]> message(StompHeaderAccessor accessor) {
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  void connectWithValidTokenSetsUser() {
    ChatPrincipal alice = principal(Instant.now().plus(Duration.ofHours(1)));
    given(authenticator.authenticate("good")).willReturn(Optional.of(alice));
    StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer good");

    interceptor.preSend(message(accessor), channel);

    assertThat(ChatPrincipal.from(accessor.getUser())).contains(alice);
  }

  @Test
  void connectWithoutHeaderIsRejected() {
    StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);

    assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
        .isInstanceOf(StompAuthException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.LOGIN_REQUIRED);
  }

  @Test
  void connectWithExpiredTokenIsRejectedAsExpired() {
    given(authenticator.authenticate("old")).willReturn(Optional.empty());
    given(tokenProvider.isExpired("old")).willReturn(true);
    StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer old");

    assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
        .isInstanceOf(StompAuthException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.TOKEN_EXPIRED);
  }

  @Test
  void connectWithInvalidTokenIsRejectedAsLoginRequired() {
    given(authenticator.authenticate("bad")).willReturn(Optional.empty());
    given(tokenProvider.isExpired("bad")).willReturn(false);
    StompHeaderAccessor accessor = accessor(StompCommand.CONNECT);
    accessor.setNativeHeader("Authorization", "Bearer bad");

    assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
        .isInstanceOf(StompAuthException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.LOGIN_REQUIRED);
  }

  @Test
  void sendWithLivePrincipalPasses() {
    given(tokenDenylist.isDenied("jti-1")).willReturn(false);
    StompHeaderAccessor accessor = accessor(StompCommand.SEND);
    accessor.setUser(principal(Instant.now().plus(Duration.ofHours(1))).toAuthentication());

    Message<?> result = interceptor.preSend(message(accessor), channel);

    assertThat(result).isNotNull();
  }

  @Test
  void sendWithExpiredPrincipalIsRejected() {
    StompHeaderAccessor accessor = accessor(StompCommand.SEND);
    accessor.setUser(principal(Instant.now().minus(Duration.ofSeconds(1))).toAuthentication());

    assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
        .isInstanceOf(StompAuthException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.TOKEN_EXPIRED);
  }

  @Test
  void subscribeWithDeniedJtiIsRejected() {
    given(tokenDenylist.isDenied("jti-1")).willReturn(true);
    StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE);
    accessor.setUser(principal(Instant.now().plus(Duration.ofHours(1))).toAuthentication());

    assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
        .isInstanceOf(StompAuthException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.LOGIN_REQUIRED);
  }

  @Test
  void sendWithoutUserIsRejected() {
    StompHeaderAccessor accessor = accessor(StompCommand.SEND);

    assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
        .isInstanceOf(StompAuthException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.LOGIN_REQUIRED);
  }

  @Test
  void disconnectIsNotChecked() {
    StompHeaderAccessor accessor = accessor(StompCommand.DISCONNECT);

    assertThat(interceptor.preSend(message(accessor), channel)).isNotNull();
  }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.StompAuthChannelInterceptorTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

`StompAuthException.java`:

```java
package com.example.chat.auth;

import com.example.chat.global.exception.ErrorCode;
import lombok.Getter;
import org.springframework.messaging.MessagingException;

// 인터셉터에서 던지면 StompSubProtocolHandler가 잡아 ERROR 프레임으로 바꾼다 (StompErrorHandler가 code 헤더를 붙임).
@Getter
public class StompAuthException extends MessagingException {

  private final ErrorCode errorCode;

  public StompAuthException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
```

`StompAuthChannelInterceptor.java`:

```java
package com.example.chat.auth;

import com.example.chat.global.exception.ErrorCode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

// HTTP 필터와 달리 STOMP에는 공개 엔드포인트가 없으므로 CONNECT를 "막는다" — board 패턴 이식의 유일한 의도적 차이.
// 이후 SEND/SUBSCRIBE마다 만료·denylist를 재검사해 로그아웃·만료가 장수명 연결에도 반영되게 한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

  private final BearerTokenAuthenticator authenticator;
  private final JwtTokenProvider tokenProvider;
  private final TokenDenylist tokenDenylist;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    switch (accessor.getCommand()) {
      case CONNECT -> authenticateConnect(accessor);
      case SEND, SUBSCRIBE -> requireLivePrincipal(accessor);
      default -> {
      }
    }
    return message;
  }

  private void authenticateConnect(StompHeaderAccessor accessor) {
    String token = BearerTokenAuthenticator
        .extractToken(accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION))
        .orElseThrow(() -> new StompAuthException(ErrorCode.LOGIN_REQUIRED));
    ChatPrincipal principal = authenticator.authenticate(token)
        .orElseThrow(() -> new StompAuthException(
            tokenProvider.isExpired(token) ? ErrorCode.TOKEN_EXPIRED : ErrorCode.LOGIN_REQUIRED));
    accessor.setUser(principal.toAuthentication());
    log.debug("STOMP CONNECT authenticated: username={}", principal.username());
  }

  private void requireLivePrincipal(StompHeaderAccessor accessor) {
    ChatPrincipal principal = ChatPrincipal.from(accessor.getUser())
        .orElseThrow(() -> new StompAuthException(ErrorCode.LOGIN_REQUIRED));
    if (principal.isExpired(Instant.now())) {
      throw new StompAuthException(ErrorCode.TOKEN_EXPIRED);
    }
    if (tokenDenylist.isDenied(principal.jti())) {
      throw new StompAuthException(ErrorCode.LOGIN_REQUIRED);
    }
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.auth.StompAuthChannelInterceptorTest'`
Expected: PASS (9 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/chat/auth/StompAuthException.java src/main/java/com/example/chat/auth/StompAuthChannelInterceptor.java src/test/java/com/example/chat/auth/StompAuthChannelInterceptorTest.java
git commit -m "feat: StompAuthChannelInterceptor — CONNECT 인증 + SEND/SUBSCRIBE 만료·denylist 재검사"
```

---

### Task 9: WebSocketConfig + StompErrorHandler + echo (통합 테스트)

**Files:**
- Create: `src/main/java/com/example/chat/global/config/StompErrorHandler.java`
- Create: `src/main/java/com/example/chat/global/config/WebSocketConfig.java`
- Create: `src/main/java/com/example/chat/message/dto/EchoRequest.java`
- Create: `src/main/java/com/example/chat/message/dto/EchoResponse.java`
- Create: `src/main/java/com/example/chat/message/EchoController.java`
- Test: `src/test/java/com/example/chat/message/StompAuthIntegrationTest.java`

**Interfaces:**
- Consumes: `StompAuthChannelInterceptor`, `StompAuthException.getErrorCode()`, `ErrorCode`
- Produces: 엔드포인트 `/ws`, prefix `/app`·`/topic`·`/queue`·`/user`, `SEND /app/echo {content}` → `/topic/echo {sender, content, sentAt}`, ERROR 프레임 헤더 `code`·`message` + text/plain 본문

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.example.chat.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chat.message.dto.EchoRequest;
import com.example.chat.message.dto.EchoResponse;
import com.example.chat.support.InMemoryTokenDenylist;
import com.example.chat.support.TestJwtFactory;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompAuthIntegrationTest {

  @LocalServerPort int port;
  @Autowired InMemoryTokenDenylist denylist;

  private WebSocketStompClient client;
  private StompSession session;

  @BeforeEach
  void setUp() {
    denylist.clear();
    client = new WebSocketStompClient(new StandardWebSocketClient());
    // ERROR 프레임은 text/plain, 데이터 프레임은 JSON — 둘 다 받으려고 composite
    client.setMessageConverter(new CompositeMessageConverter(
        List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
  }

  @AfterEach
  void tearDown() {
    if (session != null && session.isConnected()) {
      session.disconnect();
    }
  }

  private String url() {
    return "ws://localhost:" + port + "/ws";
  }

  private StompHeaders bearer(String token) {
    StompHeaders headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + token);
    return headers;
  }

  // CONNECTED 전에 오는 ERROR 프레임을 잡는다 (DefaultStompSession이 handleFrame으로 넘겨준다)
  static class ErrorCapture extends StompSessionHandlerAdapter {
    final CompletableFuture<StompHeaders> error = new CompletableFuture<>();

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      error.complete(headers);
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
      // 서버가 ERROR 후 연결을 닫으면 여기로도 온다 — ERROR 캡처가 우선이므로 무시
    }
  }

  @Test
  void connectsWithValidTokenAndReceivesEcho() throws Exception {
    session = client.connectAsync(url(), new WebSocketHttpHeaders(),
        bearer(TestJwtFactory.token("alice")), new StompSessionHandlerAdapter() {})
        .get(5, TimeUnit.SECONDS);

    CompletableFuture<EchoResponse> received = new CompletableFuture<>();
    session.subscribe("/topic/echo", new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return EchoResponse.class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        received.complete((EchoResponse) payload);
      }
    });
    Thread.sleep(300); // 인바운드 채널이 SUBSCRIBE를 처리할 시간 (simple broker는 receipt를 보내지 않는다)
    session.send("/app/echo", new EchoRequest("hello"));

    EchoResponse response = received.get(5, TimeUnit.SECONDS);
    assertThat(response.sender()).isEqualTo("alice");
    assertThat(response.content()).isEqualTo("hello");
    assertThat(response.sentAt()).isNotNull();
  }

  @Test
  void rejectsConnectWithoutToken() throws Exception {
    ErrorCapture capture = new ErrorCapture();

    client.connectAsync(url(), new WebSocketHttpHeaders(), new StompHeaders(), capture);

    StompHeaders error = capture.error.get(5, TimeUnit.SECONDS);
    assertThat(error.getFirst("code")).isEqualTo("LOGIN_REQUIRED");
  }

  @Test
  void rejectsConnectWithExpiredTokenAsTokenExpired() throws Exception {
    ErrorCapture capture = new ErrorCapture();

    client.connectAsync(url(), new WebSocketHttpHeaders(),
        bearer(TestJwtFactory.expiredToken("alice")), capture);

    StompHeaders error = capture.error.get(5, TimeUnit.SECONDS);
    assertThat(error.getFirst("code")).isEqualTo("TOKEN_EXPIRED");
  }

  @Test
  void rejectsConnectWhenJtiDenied() throws Exception {
    String token = TestJwtFactory.tokenWithJti("alice", "jti-ws-logout", Duration.ofHours(1));
    denylist.deny("jti-ws-logout");
    ErrorCapture capture = new ErrorCapture();

    client.connectAsync(url(), new WebSocketHttpHeaders(), bearer(token), capture);

    StompHeaders error = capture.error.get(5, TimeUnit.SECONDS);
    assertThat(error.getFirst("code")).isEqualTo("LOGIN_REQUIRED");
  }

  @Test
  void rejectsSendAfterLogout() throws Exception {
    String token = TestJwtFactory.tokenWithJti("alice", "jti-live", Duration.ofHours(1));
    ErrorCapture capture = new ErrorCapture();
    session = client.connectAsync(url(), new WebSocketHttpHeaders(), bearer(token), capture)
        .get(5, TimeUnit.SECONDS);

    denylist.deny("jti-live"); // board 로그아웃을 흉내낸다
    session.send("/app/echo", new EchoRequest("after logout"));

    StompHeaders error = capture.error.get(5, TimeUnit.SECONDS);
    assertThat(error.getFirst("code")).isEqualTo("LOGIN_REQUIRED");
  }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.message.StompAuthIntegrationTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

`StompErrorHandler.java`:

```java
package com.example.chat.global.config;

import com.example.chat.auth.StompAuthException;
import com.example.chat.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

// @RestControllerAdvice는 STOMP 경로를 못 본다. 인터셉터에서 던진 예외를 ERROR 프레임(code 헤더)으로 바꾼다.
// 예상 외 예외는 INTERNAL_ERROR로 숨기고 서버 로그에만 남긴다 (REST의 GlobalExceptionHandler와 같은 원칙).
@Slf4j
@Component
public class StompErrorHandler extends StompSubProtocolErrorHandler {

  @Override
  public Message<byte[]> handleClientMessageProcessingError(
      Message<byte[]> clientMessage, Throwable ex) {
    ErrorCode errorCode = findErrorCode(ex);
    if (errorCode == ErrorCode.INTERNAL_ERROR) {
      log.error("STOMP 처리 중 예상치 못한 오류", ex);
    } else {
      log.warn("STOMP 거부: code={}", errorCode.name());
    }

    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
    accessor.setMessage(errorCode.getMessage());
    accessor.setNativeHeader("code", errorCode.name());
    accessor.setContentType(MimeTypeUtils.TEXT_PLAIN);
    accessor.setLeaveMutable(true);

    StompHeaderAccessor clientAccessor = clientMessage == null ? null
        : MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
    byte[] payload = errorCode.getMessage().getBytes(StandardCharsets.UTF_8);
    return handleInternal(accessor, payload, ex, clientAccessor);
  }

  private static ErrorCode findErrorCode(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof StompAuthException authException) {
        return authException.getErrorCode();
      }
    }
    return ErrorCode.INTERNAL_ERROR;
  }
}
```

`WebSocketConfig.java`:

```java
package com.example.chat.global.config;

import com.example.chat.auth.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// 내장 simple broker (단일 인스턴스). 브로커 교체가 필요하면 이 클래스와 ChatMessagePublisher만 바꾼다.
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final StompAuthChannelInterceptor authInterceptor;
  private final StompErrorHandler errorHandler;
  private final String[] allowedOriginPatterns;

  public WebSocketConfig(
      StompAuthChannelInterceptor authInterceptor,
      StompErrorHandler errorHandler,
      @Value("${app.ws.allowed-origin-patterns}") String[] allowedOriginPatterns) {
    this.authInterceptor = authInterceptor;
    this.errorHandler = errorHandler;
    this.allowedOriginPatterns = allowedOriginPatterns;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.setErrorHandler(errorHandler);
    // 네이티브 WebSocket만 (SockJS 없음). 핸드셰이크는 공개, 인증은 CONNECT 프레임에서.
    registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOriginPatterns);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue");
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authInterceptor);
  }
}
```

`EchoRequest.java` (`message/dto/`):

```java
package com.example.chat.message.dto;

public record EchoRequest(String content) {
}
```

`EchoResponse.java` (`message/dto/`):

```java
package com.example.chat.message.dto;

import java.time.Instant;

public record EchoResponse(String sender, String content, Instant sentAt) {
}
```

`EchoController.java` (`message/`):

```java
package com.example.chat.message;

import com.example.chat.message.dto.EchoRequest;
import com.example.chat.message.dto.EchoResponse;
import java.security.Principal;
import java.time.Instant;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

// 1일차 검증용. Principal은 CONNECT에서 accessor.setUser로 심은 Authentication이라 getName()=username.
// 2일차에 방 단위 메시지(ChatMessageController)로 대체된다.
@Controller
public class EchoController {

  @MessageMapping("/echo")
  @SendTo("/topic/echo")
  public EchoResponse echo(EchoRequest request, Principal principal) {
    return new EchoResponse(principal.getName(), request.content(), Instant.now());
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test`
Expected: PASS 전체 (StompAuthIntegrationTest 5개 포함). 실패 시 `build/reports/tests/test/index.html` 확인. `rejectsSendAfterLogout`가 ERROR를 못 받으면 `StompSubProtocolHandler`가 인터셉터 예외를 `handleError`로 넘기는지 로그(`com.example.chat: debug`)를 본다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/chat/global/config/StompErrorHandler.java src/main/java/com/example/chat/global/config/WebSocketConfig.java src/main/java/com/example/chat/message src/test/java/com/example/chat/message
git commit -m "feat: STOMP 엔드포인트 /ws + simple broker + ERROR 프레임 code 헤더 + echo 브로드캐스트"
```

---

### Task 10: STOMP 수동 검증 콘솔 (정적 페이지)

**Files:**
- Create: `src/main/resources/static/index.html`
- Test: `src/test/java/com/example/chat/StaticConsoleTest.java`

**Interfaces:**
- Consumes: `SecurityConfig`의 `GET /`, `/index.html` permitAll
- Produces: 브라우저에서 토큰을 붙여 CONNECT/SUBSCRIBE/SEND를 눌러볼 수 있는 페이지 (수업 데모용)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.example.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StaticConsoleTest {

  @Autowired MockMvc mockMvc;

  @Test
  void consolePageIsPublic() throws Exception {
    mockMvc.perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("STOMP")));
  }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.StaticConsoleTest'`
Expected: FAIL (404)

- [ ] **Step 3: index.html 작성** (`src/main/resources/static/index.html`)

```html
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <title>chat STOMP console</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 760px; margin: 2rem auto; padding: 0 1rem; }
    label { display: block; margin-top: .75rem; font-weight: 600; }
    input, textarea { width: 100%; box-sizing: border-box; padding: .4rem; }
    button { margin: .5rem .25rem 0 0; padding: .4rem .8rem; }
    #log { background: #111; color: #ddd; padding: .75rem; height: 260px; overflow: auto; white-space: pre-wrap; font-family: ui-monospace, monospace; font-size: .85rem; }
  </style>
</head>
<body>
  <h1>chat STOMP console</h1>
  <p>board에서 로그인해 받은 access token을 붙여 넣고 CONNECT를 누른다. 로그아웃 후 SEND 하면 ERROR(LOGIN_REQUIRED)가 온다.</p>

  <label for="token">access token</label>
  <input id="token" placeholder="eyJhbGciOi...">

  <div>
    <button id="connect">CONNECT</button>
    <button id="subscribe" disabled>SUBSCRIBE /topic/echo</button>
    <button id="disconnect" disabled>DISCONNECT</button>
  </div>

  <label for="content">message</label>
  <input id="content" value="hello">
  <button id="send" disabled>SEND /app/echo</button>

  <label>log</label>
  <div id="log"></div>

  <script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs@7.1.1/bundles/stomp.umd.min.js"></script>
  <script>
    const $ = (id) => document.getElementById(id);
    const log = (line) => { $('log').textContent += `[${new Date().toLocaleTimeString()}] ${line}\n`; $('log').scrollTop = $('log').scrollHeight; };
    const setConnected = (on) => { $('subscribe').disabled = !on; $('send').disabled = !on; $('disconnect').disabled = !on; $('connect').disabled = on; };

    let client = null;

    $('connect').onclick = () => {
      const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
      client = new StompJs.Client({
        brokerURL: wsUrl,
        connectHeaders: { Authorization: 'Bearer ' + $('token').value.trim() },
        reconnectDelay: 0,
        onConnect: () => { log('CONNECTED'); setConnected(true); },
        onStompError: (frame) => { log(`ERROR code=${frame.headers.code} message=${frame.headers.message}`); setConnected(false); },
        onWebSocketClose: () => { log('websocket closed'); setConnected(false); },
        debug: (msg) => { if (msg.startsWith('>>>') || msg.startsWith('<<<')) log(msg.trim()); },
      });
      client.activate();
    };

    $('subscribe').onclick = () => {
      client.subscribe('/topic/echo', (message) => log('MESSAGE ' + message.body));
      log('subscribed /topic/echo');
    };

    $('send').onclick = () => {
      client.publish({ destination: '/app/echo', body: JSON.stringify({ content: $('content').value }) });
    };

    $('disconnect').onclick = () => { client.deactivate(); setConnected(false); log('DISCONNECT'); };
  </script>
</body>
</html>
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew --no-daemon test --tests 'com.example.chat.StaticConsoleTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/static/index.html src/test/java/com/example/chat/StaticConsoleTest.java
git commit -m "feat: STOMP 수동 검증 콘솔 페이지 (/index.html, 공개)"
```

---

### Task 11: 로컬 도커 실행 (board-redis 접근 경로) + 실기동 검증

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `docker-compose.yml`
- Create: `docker-compose.local.yml`
- Create: `scripts/dev_redis_proxy.sh`
- Create: `README.md`

**Interfaces:**
- Consumes: `.env` (`JWT_SECRET`, `DB_PASSWORD` 등), external 네트워크 `board-db-net`, 컨테이너 `mysql-8`, `board-redis`
- Produces: `docker compose -f docker-compose.yml -f docker-compose.local.yml up --build` 로 `http://localhost:8092` 기동. `bootRun` 경로용 Redis 터널 스크립트.

- [ ] **Step 1: Dockerfile 작성** (board의 것을 chat에 맞게 축소)

```dockerfile
# syntax=docker/dockerfile:1

# ── 1) build stage ── Gradle 래퍼로 bootJar만 만든다. 테스트는 CI에서 별도 수행 (-x test).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ── 2) runtime stage ── JRE + 비루트 사용자. 헬스체크용 curl만 설치.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --home-dir /app spring

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown -R spring:spring /app
USER spring

EXPOSE 8092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`.dockerignore`:

```
.git
.gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.env
*.log
.idea/
*.iml
.vscode/
.DS_Store
docs/
scripts/
frontend/
```

- [ ] **Step 2: docker-compose.yml 작성**

```yaml
# chat 스택 — board compose가 만든 external 네트워크(board-db-net)에 합류해
# mysql-8 / board-redis 에 컨테이너명으로 접근한다. board 쪽 파일은 건드리지 않는다.
#
#   로컬:   docker compose -f docker-compose.yml -f docker-compose.local.yml up --build
#   서버:   docker compose up -d --no-build --wait   (이미지는 CI가 GHCR에 push)
name: chat

services:
  app:
    build:
      context: .
      dockerfile: Dockerfile
    image: ghcr.io/icesnake72/chat-app:latest
    container_name: chat-app
    env_file:
      - .env                       # JWT_SECRET, DB_PASSWORD 등
    environment:
      DB_HOST: mysql-8
      DB_PORT: "3306"
      DB_NAME: ${DB_NAME:-chat}
      REDIS_HOST: board-redis      # 컨테이너명 (board compose의 서비스명 redis 대신)
      REDIS_PORT: "6379"
      TZ: Asia/Seoul
    networks:
      - board-db-net
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8092/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 40s
    restart: unless-stopped

networks:
  board-db-net:
    external: true
```

`docker-compose.local.yml` (로컬 개발에서만 호스트 포트 개방):

```yaml
services:
  app:
    ports:
      - "127.0.0.1:8092:8092"
```

- [ ] **Step 3: scripts/dev_redis_proxy.sh 작성** (`./gradlew bootRun` 용 터널)

```bash
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
```

- [ ] **Step 4: README.md 작성**

```markdown
# chat

board(`../board`) 인증을 재사용하는 STOMP 채팅 서버. 설계: `docs/design/2026-09-12-stomp-chat-design.md`.

## 로컬 실행

전제: board compose가 떠 있어 `mysql-8`, `board-redis`, `board-app`이 `board-db-net`에 있다.

1. `cp .env.example .env` 후 `JWT_SECRET`(board와 동일 값), `DB_PASSWORD`를 채운다.
2. `set -a; source .env; set +a; scripts/init_db.sh` — `chat` DB 생성 (1회)
3. 실행 방법 중 하나
   - 도커: `docker compose -f docker-compose.yml -f docker-compose.local.yml up --build`
   - bootRun: `scripts/dev_redis_proxy.sh && ./gradlew bootRun` (`.env`의 `REDIS_PORT=6380`)
4. `http://localhost:8092/index.html` 에서 board 토큰으로 CONNECT

board 토큰 얻기 (로컬 board는 caddy 경유 `http://localhost`):

    curl -s -X POST http://localhost/api/v1/auth/login \
      -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"admin1234"}'

## 테스트

    ./gradlew test
```

- [ ] **Step 5: 도커 빌드·기동 검증**

Run:

```bash
chmod +x scripts/dev_redis_proxy.sh
set -a; source .env; set +a; scripts/init_db.sh
docker compose -f docker-compose.yml -f docker-compose.local.yml up --build -d --wait
curl -s http://localhost:8092/actuator/health
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8092/api/v1/chat/me
```

Expected: health `{"status":"UP"}`, `/me` 무토큰 → `401`. `.env`에 `JWT_SECRET`이 비어 있으면 기동 실패(`Could not resolve placeholder 'JWT_SECRET'`)가 정상 동작이다 — 값을 채운 뒤 재시도.

- [ ] **Step 6: board 토큰으로 E2E 검증** (JWT_SECRET이 채워진 경우)

```bash
TOKEN=$(curl -s -X POST http://localhost/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin1234"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -s http://localhost:8092/api/v1/chat/me -H "Authorization: Bearer $TOKEN"
```

Expected: `{"userId":1,"username":"admin","nickname":"관리자"}` (id는 board DB 기준). 브라우저 `http://localhost:8092/index.html`에서 같은 토큰으로 CONNECT → SUBSCRIBE → SEND 하면 `MESSAGE {"sender":"admin",...}`. board에서 `POST /api/v1/auth/logout`(Authorization 헤더 포함) 후 SEND 하면 `ERROR code=LOGIN_REQUIRED`.

- [ ] **Step 7: 커밋**

```bash
git add Dockerfile .dockerignore docker-compose.yml docker-compose.local.yml scripts README.md
git commit -m "feat: 로컬 도커 실행 — board-db-net 합류 compose, Dockerfile, bootRun용 redis 터널, README"
```

---

### Task 12: 1일차 walkthrough 문서

**Files:**
- Create: `docs/lecture/day1_auth_stomp_walkthrough.md`

**Interfaces:**
- Consumes: Task 1~11의 최종 코드 (문서의 스니펫은 반드시 실제 파일을 읽어 현재 코드와 일치시킨다)

- [ ] **Step 1: md-docs 규약으로 문서 작성**

구성(번호 H2 + `---` 구분, 첫 섹션은 요약표, 마지막 섹션은 실무 기준):

1. 핵심 요약 — 이 수업에서 만드는 것, 4시간 배분표, board와의 차이 3가지
2. 사전 준비 (강사) — `.env`, chat DB, jjwt 호환 확인, 예외 계층은 미리 복사
3. 0:00 board 인증 복습 — claims 구조, denylist, refresh 쿠키 path 함정 (mermaid sequenceDiagram)
4. 0:30 JwtTokenProvider → JwtAuthenticationFilter → SecurityConfig — 코드 + curl 401/200
5. 1:30 TokenDenylist → BoardUserReader → ChatPrincipal → `/me` — 로그아웃 후 401 실측
6. 2:15 테스트 — H2 `schema-board.sql`, `TestJwtFactory`, `InMemoryTokenDenylist`가 필요한 이유
7. 2:45 STOMP — WebSocketConfig, 인터셉터(CONNECT는 막는다), StompErrorHandler, echo, 콘솔 데모 (mermaid flowchart)
8. 자주 나오는 질문 / 함정 표 — origin 403, `JWT_SECRET` 불일치, Redis 오연결(`my-redis`), Boot 4 패키지 이동
9. 다음 수업 예고 (2일차 방 도메인) + 실무 선택 기준

- [ ] **Step 2: 문서 self-check**

md-docs 체크: 첫 섹션만으로 핵심 전달, 표 셀 3줄 이하, 섹션 번호·구분선, mermaid 문법(라벨 `["..."]`, sequenceDiagram 메시지에 콜론 없음), ASCII 박스 없음, 들여쓰기 2칸.

- [ ] **Step 3: 커밋**

```bash
git add docs/lecture/day1_auth_stomp_walkthrough.md
git commit -m "docs: 1일차 인증 연동 + STOMP CONNECT walkthrough (코드 무변경)"
```

---

## Self-Review 결과

- **Spec 커버리지**: 설계 2.3(auth 패키지 전부), 5.1/5.2(CONNECT·SEND·SUBSCRIBE·ERROR), 5.4(인증 규칙 표 전체), 6.2 `/me`, 7.1~7.3(예외 계층·STOMP 처리 경로), 10.1(테스트 지원 4종) 커버. 5.5 presence, 6.2 나머지 REST, 7.2의 방 관련 코드, heartbeat는 2일차 이후 범위로 의도적 제외.
- **Placeholder**: 없음. Task 12는 문서 작업이라 구성 목차만 명시.
- **타입 일관성**: `ChatPrincipal(Long, String, String, String, Instant)` 5개 필드, `TokenClaims(String, String, Instant)`, `BearerTokenAuthenticator.extractToken`(static)·`authenticate`, `StompAuthException.getErrorCode()`, `InMemoryTokenDenylist.deny/clear`가 Task 3~10에서 동일하게 사용됨.
