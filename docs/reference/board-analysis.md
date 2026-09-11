# board 프로젝트 분석 보고서 (읽기 전용)

대상: `/Users/eunbumkim/Documents/practice/java/board`
목적: `/Users/eunbumkim/Documents/practice/java/chat` 에서 STOMP 기반 채팅 서버/클라이언트를 만들 때 board의 인증 기능을 재사용하기 위한 전수 조사
제외: `.git`, `build`, `.gradle`, `.playwright-mcp`, `node_modules`, `uploads`
비밀 취급: `.env` 는 키 이름만 기재, `webserver_key.pem` 과 `scripts/ls_server_key.pem` 은 열지 않음, `jwt.secret` 기본값은 마스킹
파일 수정: 없음 (read-only)

---

# 1. 빌드 / 스택

**`/Users/eunbumkim/Documents/practice/java/board/build.gradle`**

```gradle
plugins {
	id 'java'
	id 'org.springframework.boot' version '3.5.15'
	id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

// 단계 6: @PreAuthorize SpEL의 #id가 컨트롤러 파라미터 이름으로 바인딩되려면 -parameters가 필요하다.
// (Spring Boot 플러그인이 기본 적용하지만 명시해 보장한다)
tasks.named('compileJava') {
	options.compilerArgs << '-parameters'
}

repositories {
	mavenCentral()
}

dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
	implementation 'org.springframework.boot:spring-boot-starter-security'
	// 단계 8: OAuth 표준화 — 단계 7 수동 구현(KakaoOAuthClient 등)이 하던 일을 표준 부품으로
	implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
	implementation 'org.springframework.boot:spring-boot-starter-validation'
	implementation 'org.springframework.boot:spring-boot-starter-web'
	// 단계 15: refresh token 저장소(TTL)·access denylist — Redis(StringRedisTemplate)
	implementation 'org.springframework.boot:spring-boot-starter-data-redis'
	implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
	runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
	runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
	compileOnly 'org.projectlombok:lombok'
	runtimeOnly 'com.mysql:mysql-connector-j'
	annotationProcessor 'org.projectlombok:lombok'
	// 단계 7: 커스텀 @ConfigurationProperties(app.oauth.kakao.*) 메타데이터 생성
	annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.security:spring-security-test'
	testRuntimeOnly 'com.h2database:h2'
	testCompileOnly 'org.projectlombok:lombok'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
	testAnnotationProcessor 'org.projectlombok:lombok'
}

tasks.named('test') {
	useJUnitPlatform()
}
```

**`/Users/eunbumkim/Documents/practice/java/board/settings.gradle`**

```gradle
rootProject.name = 'board'
```

단일 모듈 프로젝트입니다.

**`gradle/wrapper/gradle-wrapper.properties`**

```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.5-bin.zip
```

정리하면 다음과 같습니다.

| 항목 | 값 |
|---|---|
| Spring Boot | 3.5.15 |
| Java | 21 (toolchain) |
| Gradle | 8.14.5 (wrapper) |
| DB 드라이버 | mysql-connector-j (runtimeOnly), 테스트는 H2 |
| JWT | jjwt 0.12.6 (api / impl / jackson) |
| 기타 | Lombok, validation, oauth2-client, data-redis |

`-parameters` 옵션이 켜져 있습니다. `@PreAuthorize` SpEL과 `@ConfigurationProperties` 생성자 바인딩이 이에 의존합니다.

**WebSocket / STOMP 의존성은 없습니다.** 채팅 서버는 `spring-boot-starter-websocket`을 새로 추가해야 합니다.

---

# 2. 인증 구조 (가장 중요)

## 2-1. 전체 그림

| 항목 | 값 |
|---|---|
| 인증 방식 | Stateless JWT (HS256) + opaque refresh token |
| JWT 라이브러리 | jjwt 0.12.6 |
| secret 설정 키 | `jwt.secret` (Base64 문자열), 환경변수 `JWT_SECRET` |
| 서명 알고리즘 | HS256 (`Keys.hmacShaKeyFor` + `Decoders.BASE64`) |
| access token 수명 | `jwt.access-token-validity-seconds: 3600` (1시간) |
| refresh token 수명 | `jwt.refresh-token-validity-seconds: 1209600` (14일) |
| access 저장 위치 | 응답 JSON 본문 -> 클라이언트 **메모리** |
| refresh 저장 위치 | **httpOnly 쿠키** + 서버측 **Redis** |
| refresh 형식 | opaque UUID (JWT 아님) |
| refresh 회전 | **미적용** (재발급 시 access만 갱신) |
| 세션 | `SessionCreationPolicy.STATELESS` |
| CSRF | disable |
| CORS | **코드/설정 어디에도 없음** (nginx same-origin 프록시로 회피) |

## 2-2. JWT claims 구조

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/jwt/JwtTokenProvider.java`** (전문)

```java
package com.example.board.auth.jwt;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 강의 포인트: 서버는 토큰을 검증만 할 뿐 어떤 로그인 상태도 저장하지 않는다(stateless).
// 사용자 식별 정보(username)는 서명된 토큰 안에 담겨 매 요청마다 클라이언트가 보내온다.
// subject를 username으로 둔 이유: 검증 후 UserDetailsService.loadUserByUsername을 그대로 재사용하기 위함(표준).
@Slf4j
@Component
public class JwtTokenProvider {

  private final SecretKey key;
  private final long accessTokenValiditySeconds;

  public JwtTokenProvider(
      @Value("${jwt.secret}") String base64Secret,
      @Value("${jwt.access-token-validity-seconds}") long accessTokenValiditySeconds) {
    // Base64 시크릿을 바이트로 디코드해 HMAC 서명용 SecretKey 생성 (HS256, 최소 32바이트)
    this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    this.accessTokenValiditySeconds = accessTokenValiditySeconds;
  }

  // username을 subject에 담고 만료시각을 정해 서명된 토큰 문자열을 발급한다
  public String createToken(String username) {
    Date now = new Date();
    Date expiration = new Date(now.getTime() + accessTokenValiditySeconds * 1000);
    return Jwts.builder()
        .subject(username)                   // sub 클레임 = username
        .id(UUID.randomUUID().toString())    // jti = 토큰 고유 식별자 — denylist의 키 (단계 15)
        .issuedAt(now)                       // iat = 발급 시각
        .expiration(expiration)              // exp = 만료 시각
        .signWith(key)                       // 시크릿으로 서명 (위조 방지)
        .compact();                          // 헤더.페이로드.서명 문자열로 직렬화
  }

  // 토큰을 검증하며 파싱해 subject(username)를 꺼낸다 (서명/만료 불량이면 예외)
  public String getUsername(String token) {
    return parseClaims(token).getSubject();
  }

  // 단계 15: 토큰 고유 식별자(jti) — 폐기(denylist) 등록·조회의 키
  public String getJti(String token) {
    return parseClaims(token).getId();
  }

  // 단계 15: 토큰의 남은 유효초 — denylist 항목의 TTL로 쓴다(자연 만료와 함께 키도 소멸)
  public long getRemainingSeconds(String token) {
    long remainMillis = parseClaims(token).getExpiration().getTime() - System.currentTimeMillis();
    return Math.max(1, remainMillis / 1000);   // 최소 1초 — 이미 만료 직전이어도 등록은 성립
  }

  private io.jsonwebtoken.Claims parseClaims(String token) {
    return Jwts.parser()
        .verifyWith(key)            // 서명 검증
        .build()
        .parseSignedClaims(token)   // 검증 실패·만료 시 예외 발생
        .getPayload();
  }

  // 서명 위변조·만료·형식 오류만 "인증 실패(false)"로 취급한다.
  // JwtException(만료·서명·형식), IllegalArgumentException(null/빈 토큰)만 잡고,
  // 그 외 예외(키 로딩 실패 등 내부 오류)는 삼키지 않고 전파한다 — 401로 둔갑하지 않도록.
  public boolean validateToken(String token) {
    try {
      Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      log.debug("invalid jwt: {}", e.getMessage());
      return false;
    }
  }
}
```

**채팅 서버 설계에 결정적인 두 가지 사실이 있습니다.**

1. **claims에 roles도 userId도 없습니다.** `sub`, `jti`, `iat`, `exp` 가 전부입니다. 권한과 `userId` 는 매 요청 DB에서 `loadUserByUsername` 으로 다시 읽습니다.
2. `sub` 가 **username** 입니다. 초기 버전은 userId였으나 단계 3에서 username으로 바뀌었습니다. STOMP `Principal.getName()` 을 username으로 잡으면 board와 일관됩니다.

## 2-3. JwtAuthenticationFilter (전문)

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/jwt/JwtAuthenticationFilter.java`**

```java
package com.example.board.auth.jwt;

import com.example.board.auth.CustomUserDetailsService;
import com.example.board.auth.token.TokenDenylist;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

// 강의 포인트: Bearer 토큰을 검증해 인증 정보를 SecurityContext에 "심기"만 한다.
// 막지 않는 이유: 공개 엔드포인트가 있으므로, 인증/인가 판단은 뒤따르는 SecurityFilterChain이 맡는다.
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenProvider tokenProvider;
  private final CustomUserDetailsService userDetailsService;
  // 단계 15: 폐기된 access token(로그아웃 등)을 즉시 거부하기 위한 denylist 조회
  private final TokenDenylist tokenDenylist;
  // 필터 단계에서 발생한 "내부 오류"를 DispatcherServlet 예외 처리로 위임하기 위한 리졸버.
  // 필터는 @RestControllerAdvice(GlobalExceptionHandler)의 사각지대라, 이걸 거쳐야 일관된 500 응답이 된다.
  private final HandlerExceptionResolver handlerExceptionResolver;

  // @Qualifier가 필요해 생성자를 명시한다 — HandlerExceptionResolver 빈이 여러 개라 이름으로 특정한다.
  public JwtAuthenticationFilter(
      JwtTokenProvider tokenProvider,
      CustomUserDetailsService userDetailsService,
      TokenDenylist tokenDenylist,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
    this.tokenProvider = tokenProvider;
    this.userDetailsService = userDetailsService;
    this.tokenDenylist = tokenDenylist;
    this.handlerExceptionResolver = handlerExceptionResolver;
  }

  // 요청당 한 번, 컨트롤러보다 먼저 서블릿 컨테이너가 호출한다 (OncePerRequestFilter)
  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String token = resolveToken(request);
      // 단계 15: 서명·만료 검증 통과 후 denylist(폐기 목록)도 확인한다.
      // 폐기된 토큰이면 컨텍스트를 심지 않고 통과 → 뒷단 entryPoint가 401 (기존 "막지 않는" 설계 유지).
      // Redis 장애 시 여기서 예외 → 아래 내부 오류 분기(500, fail-closed) — denylist를 우회할 수 없다.
      if (token != null && tokenProvider.validateToken(token)
          && !tokenDenylist.isDenied(tokenProvider.getJti(token))) {
        // 토큰의 username으로 UserDetails를 로딩해 표준 Authentication을 만들어 컨텍스트에 저장한다
        String username = tokenProvider.getUsername(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
      }
    } catch (AuthenticationException e) {
      // 유효 토큰인데 사용자가 없는 경우(UsernameNotFoundException 등) — "인증 실패"다.
      // 컨텍스트를 비우고 그대로 통과시키면 뒷단 entryPoint가 401로 응답한다(정상 경로).
      SecurityContextHolder.clearContext();
    } catch (Exception e) {
      // 예상치 못한 내부 오류(키 로딩 실패·DB 장애 등) — 401로 둔갑시키지 않는다.
      // DispatcherServlet 예외 처리로 위임하면 GlobalExceptionHandler가 500(INTERNAL_ERROR)로 응답한다.
      SecurityContextHolder.clearContext();
      handlerExceptionResolver.resolveException(request, response, null, e);
      return; // 위임했으므로 체인을 더 진행하지 않는다
    }
    filterChain.doFilter(request, response); // 토큰이 없거나 무효여도 막지 않고 통과
  }

  // Authorization 헤더에서 "Bearer " 접두어를 떼고 토큰 문자열만 반환 (없으면 null)
  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }
}
```

필터는 **요청을 막지 않습니다.** 인증 정보를 심기만 하고, 401 판단은 뒤따르는 `AuthorizationFilter` 와 `RestAuthenticationEntryPoint` 가 합니다. 예외 3분기(인증 실패 / 내부 오류 / 정상)가 이 프로젝트의 설계 자산입니다. Redis 장애는 fail-closed로 500이 되며 denylist를 우회할 수 없습니다.

## 2-4. SecurityConfig (전문)

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/global/config/SecurityConfig.java`**

```java
package com.example.board.global.config;

import com.example.board.auth.jwt.JwtAuthenticationFilter;
import com.example.board.auth.oauth2.CookieOAuth2AuthorizationRequestRepository;
import com.example.board.auth.oauth2.CustomOAuth2UserService;
import com.example.board.auth.oauth2.CustomOidcUserService;
import com.example.board.auth.oauth2.OAuth2LoginFailureHandler;
import com.example.board.auth.oauth2.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// 강의 단계 3 — Spring Security 표준(UserDetailsService + 선언적 인가).
// JwtAuthenticationFilter가 Bearer 토큰을 검증해 SecurityContext에 Authentication을 채우면,
// 인증/인가는 authorizeHttpRequests 규칙이 선언적으로 판단한다. 서버는 세션을 만들지 않는다(STATELESS).
// 강의 단계 6 — 메서드 보안(@EnableMethodSecurity, prePostEnabled 기본 true).
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  // BCrypt: 단방향 해시 + salt 자동 포함. 비밀번호 저장의 표준.
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  // Spring이 CustomUserDetailsService + PasswordEncoder로 DaoAuthenticationProvider를 자동 구성한다.
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }

  // 단계 8 주의 — oauth2 부품들은 생성자 필드가 아니라 "빈 메서드 파라미터"로 받는다.
  // CustomOAuth2UserService가 PasswordEncoder(이 클래스의 @Bean)를 쓰므로, 생성자로 받으면
  // SecurityConfig → CustomOAuth2UserService → PasswordEncoder → SecurityConfig 순환이 생긴다.
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      CookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository,
      CustomOAuth2UserService customOAuth2UserService,
      CustomOidcUserService customOidcUserService,
      OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
      OAuth2LoginFailureHandler oAuth2LoginFailureHandler) throws Exception {
    http
        // CSRF 보호 끔 — 쿠키 세션이 아닌 토큰 인증이라 불필요 (REST API)
        .csrf(AbstractHttpConfigurer::disable)
        // 폼 로그인 화면 끔 — 로그인은 /auth/login JSON API로 직접 처리
        .formLogin(AbstractHttpConfigurer::disable)
        // HTTP Basic 인증 끔 — Bearer 토큰만 사용
        .httpBasic(AbstractHttpConfigurer::disable)
        // 세션을 만들지 않음(stateless) — 상태는 토큰이 들고 다닌다
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // 단계 10 보안 보강: XSS/MIME sniffing 방어 헤더.
        .headers(headers -> headers
            .contentTypeOptions(Customizer.withDefaults())
            .contentSecurityPolicy(csp ->
                csp.policyDirectives("default-src 'none'; img-src 'self'; frame-ancestors 'none'")))
        .authorizeHttpRequests(auth -> auth
            // 공개: 인증/회원가입(로그아웃 포함 — stateless라 인증 불필요), 게시판·게시글 조회
            .requestMatchers("/api/v1/auth/**").permitAll()
            // 단계 7: 카카오 OAuth2 — 로그인 전 단계이므로 당연히 공개.
            .requestMatchers("/api/oauth/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/boards/**").permitAll()
            // 단계 11: 댓글 조회도 이 /posts/** 규칙에 포함되어 공개다.
            .requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()
            // 단계 10: 업로드된 게시글 이미지 정적 서빙 — 조회는 공개
            .requestMatchers(HttpMethod.GET, "/images/**").permitAll()
            // /me는 인증 필요 — 타인 프로필(/profiles/{userId})보다 먼저 매칭해야 한다
            .requestMatchers("/api/v1/profiles/me").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/v1/profiles/*").permitAll()
            .anyRequest().authenticated())
        // 단계 8: OAuth 표준화. 시작 URL은 /oauth2/authorization/kakao (표준 기본값),
        // 콜백은 /login/oauth2/code/kakao — 카카오 콘솔에 이 콜백 URI 등록 필수.
        .oauth2Login(oauth -> oauth
            // state 저장을 세션(기본값) 대신 쿠키로 — STATELESS 유지
            .authorizationEndpoint(endpoint ->
                endpoint.authorizationRequestRepository(cookieAuthorizationRequestRepository))
            // 단계 9: 두 경로를 각각 연결 — 카카오(순수 OAuth2)는 userService,
            // 구글(openid scope → OIDC)은 oidcUserService가 담당한다.
            .userInfoEndpoint(userInfo -> userInfo
                .userService(customOAuth2UserService)
                .oidcUserService(customOidcUserService))
            // 성공: 우리 JWT + refresh 쿠키 / 실패: SPA 루트로 error 쿼리 리다이렉트
            .successHandler(oAuth2LoginSuccessHandler)
            .failureHandler(oAuth2LoginFailureHandler))
        // 401/403을 우리 ErrorResponse JSON으로 응답
        .exceptionHandling(e -> e
            .authenticationEntryPoint(authenticationEntryPoint)
            .accessDeniedHandler(accessDeniedHandler))
        // 우리 JWT 필터를 표준 인증 필터 앞에 끼움 — 컨트롤러 전에 SecurityContext를 채운다
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
```

**CORS 관련 확인 결과**: `src/main` 전체에서 `cors`, `CrossOrigin`, `allowedOrigin` grep 결과 0건입니다. `CorsConfigurationSource` 빈도 `.cors(...)` 호출도 없습니다. 프론트 nginx가 same-origin 프록시를 하므로 CORS가 필요 없는 구조입니다.

## 2-5. AuthController: 엔드포인트와 DTO

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/api/v1/auth/signup` | `SignupRequest` (JSON) | 201 + `UserResponse` |
| POST | `/api/v1/auth/login` | `LoginRequest` (JSON) | 200 + `TokenResponse` + `Set-Cookie: refreshToken` |
| POST | `/api/v1/auth/reissue` | 쿠키 `refreshToken` | 200 + `TokenResponse` |
| POST | `/api/v1/auth/logout` | 쿠키 + `Authorization` 헤더 | 204 + 쿠키 만료 |

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/AuthController.java`** (전문)

```java
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final RefreshCookieFactory refreshCookieFactory;

  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse signup(@Valid @RequestBody SignupRequest request) {
    return authService.signup(request);
  }

  // 단계 5 핵심: access token은 본문(JSON)으로, refresh token은 httpOnly 쿠키(Set-Cookie)로 내려준다.
  // refresh token을 본문에 두지 않아 JS가 접근할 수 없고(XSS 탈취 방어), 브라우저가 자동으로 동봉해 보낸다.
  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    TokenPair tokens = authService.login(request);
    ResponseCookie refreshCookie =
        refreshCookieFactory.create(tokens.refreshToken(), tokens.refreshTokenValiditySeconds());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
        .body(TokenResponse.bearer(tokens.accessToken(), tokens.accessTokenValiditySeconds()));
  }

  // 단계 5: 더 이상 본문(RefreshTokenRequest)으로 받지 않고 httpOnly 쿠키에서 refresh token을 읽는다.
  // 쿠키가 없으면 401(INVALID_REFRESH_TOKEN). 새 access token만 본문으로 반환한다(refresh 쿠키는 그대로).
  @PostMapping("/reissue")
  public ResponseEntity<TokenResponse> reissue(
      @CookieValue(name = "refreshToken", required = false) String refreshToken) {
    if (refreshToken == null) {
      throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
    }
    TokenPair tokens = authService.reissue(refreshToken);
    return ResponseEntity.ok()
        .body(TokenResponse.bearer(tokens.accessToken(), tokens.accessTokenValiditySeconds()));
  }

  // 단계 5: 쿠키에서 refresh token을 읽어 서버에서 지우고(idempotent), 클라이언트 쿠키도 maxAge=0으로 만료시킨다.
  // 단계 15: Authorization 헤더의 access token도 함께 넘겨 "즉시 폐기"(denylist)한다.
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = "refreshToken", required = false) String refreshToken,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    String accessToken = null;
    if (authorization != null && authorization.startsWith("Bearer ")) {
      accessToken = authorization.substring("Bearer ".length());
    }
    authService.logout(refreshToken, accessToken);
    ResponseCookie expired = refreshCookieFactory.expire();
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, expired.toString())
        .build();
  }
}
```

**DTO 전문** (`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/dto/`)

```java
public record SignupRequest(
    @NotBlank @Size(min = 4, max = 50) String username,
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password,
    @NotBlank @Size(max = 50) String nickname
) {
}

public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password
) {
}

// 강의 포인트(단계 5): 응답 본문에는 access token만 담는다.
// refresh token은 본문이 아니라 httpOnly 쿠키로 내려간다.
public record TokenResponse(
    String accessToken,
    String tokenType,
    long expiresIn
) {

  public static TokenResponse bearer(String accessToken, long expiresIn) {
    return new TokenResponse(accessToken, "Bearer", expiresIn);
  }
}

// 강의 포인트: 서비스는 access/refresh 두 토큰을 "생성"만 하고, 전달 매체(본문 vs 쿠키)는 컨트롤러가 결정한다.
public record TokenPair(
    String accessToken,
    String refreshToken,
    long accessTokenValiditySeconds,
    long refreshTokenValiditySeconds
) {
}
```

`UserResponse` (`/.../user/dto/UserResponse.java`)

```java
public record UserResponse(
    Long id,
    String username,
    String email,
    String role
) {

  public static UserResponse from(User user) {
    return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole().name());
  }
}
```

## 2-6. AuthService (핵심 메서드 원문)

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/AuthService.java`**

```java
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  // 단계 15 처리에 의해 제거: RefreshTokenRepository(JPA) → RefreshTokenStore(Redis, TTL)
  private final RefreshTokenStore refreshTokenStore;
  private final TokenDenylist tokenDenylist;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider tokenProvider;
  private final AuthenticationManager authenticationManager;

  @Value("${jwt.access-token-validity-seconds}")
  private long accessTokenValiditySeconds;

  @Value("${jwt.refresh-token-validity-seconds}")
  private long refreshTokenValiditySeconds;

  // User와 UserProfile을 한 트랜잭션으로 생성 — 하나라도 실패하면 모두 롤백
  @Transactional
  public UserResponse signup(SignupRequest request) {
    if (userRepository.existsByUsername(request.username())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_USERNAME);
    }
    if (userRepository.existsByEmail(request.email())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_EMAIL);
    }
    if (userProfileRepository.existsByNickname(request.nickname())) {
      throw new DuplicateException(ErrorCode.NICKNAME_DUPLICATED);
    }

    User user = userRepository.save(new User(
        request.username(),
        request.email(),
        passwordEncoder.encode(request.password()),
        Role.USER));
    userProfileRepository.save(new UserProfile(user, request.nickname(), null));

    return UserResponse.from(user);
  }

  // username 미존재와 password 불일치를 같은 메시지(LOGIN_FAILED)로 응답한다 — user enumeration 방지.
  @Transactional
  public TokenPair login(LoginRequest request) {
    try {
      Authentication authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.username(), request.password()));
      CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
      // 토큰 생성까지만 책임진다. 본문(access)·쿠키(refresh) 전달은 컨트롤러가 결정.
      return createTokenPair(principal.getUsername(), principal.getId());
    } catch (AuthenticationException e) {
      throw new UnauthorizedException(ErrorCode.LOGIN_FAILED);
    }
  }

  // 단계 7: 인증 수단(로컬 password / 카카오 OAuth)과 무관하게 "인증이 끝난 사용자"에게 토큰 쌍을 발급한다.
  @Transactional
  public TokenPair issueTokenPair(User user) {
    return createTokenPair(user.getUsername(), user.getId());
  }

  private TokenPair createTokenPair(String username, Long userId) {
    String accessToken = tokenProvider.createToken(username);
    String refreshToken = issueRefreshToken(userId);
    return new TokenPair(
        accessToken, refreshToken, accessTokenValiditySeconds, refreshTokenValiditySeconds);
  }

  // refresh token은 회전(rotation)하지 않고 만료 전까지 그대로 둔다 — 회전은 후속 주제.
  @Transactional(readOnly = true)
  public TokenPair reissue(String refreshToken) {
    // 단계 15: 만료 분기(isExpired → EXPIRED_REFRESH_TOKEN)가 소멸했다 —
    // TTL이 지나면 키 자체가 사라지므로 "없음 = 무효" 한 가지로 단순해진다.
    Long userId = refreshTokenStore.findUserId(refreshToken)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));
    String newAccessToken = tokenProvider.createToken(user.getUsername());
    return new TokenPair(
        newAccessToken, refreshToken, accessTokenValiditySeconds, refreshTokenValiditySeconds);
  }

  // 로그아웃 = refresh 폐기 + access "즉시" 폐기(단계 15). 두 삭제 모두 이미 없어도 조용히 통과(멱등).
  public void logout(String refreshToken, String accessToken) {
    if (refreshToken != null) {
      refreshTokenStore.deleteByToken(refreshToken);
    }
    if (accessToken != null && tokenProvider.validateToken(accessToken)) {
      tokenDenylist.deny(
          tokenProvider.getJti(accessToken), tokenProvider.getRemainingSeconds(accessToken));
    }
  }

  // opaque 랜덤 토큰(UUID)을 만들어 한 사용자당 하나로 저장한다 — 기존이 있으면 교체.
  // 평문 UUID 저장은 강의 단순화 — 운영에선 해시 권장.
  private String issueRefreshToken(Long userId) {
    String token = UUID.randomUUID().toString();
    refreshTokenStore.save(userId, token, refreshTokenValiditySeconds);
    return token;
  }
}
```

## 2-7. Redis 토큰 저장소 (전문)

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/token/RefreshTokenStore.java`**

```java
// 단계 15: refresh token 저장소 추상화.
public interface RefreshTokenStore {

  // 사용자당 1개 불변식: 기존 토큰이 있으면 교체(옛 토큰은 즉시 무효)
  void save(Long userId, String token, long ttlSeconds);

  // 토큰으로 소유자 조회. 비어 있으면 "무효" — TTL이 만료 검사를 대신한다
  Optional<Long> findUserId(String token);

  // 로그아웃 — 이미 없어도 조용히 통과(멱등)
  void deleteByToken(String token);
}
```

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/token/RedisRefreshTokenStore.java`**

```java
// 단계 15: refresh token의 Redis 구현 — 양방향 2키 1쌍 스키마.
//   rt:{token}       → userId   (reissue/logout의 findByToken 경로)
//   rt:user:{userId} → token    (재로그인 시 기존 토큰을 찾아 폐기 — 사용자당 1개 불변식)
// 두 키 모두 TTL 14일 — 만료되면 키가 사라지므로 "없음 = 무효" 하나로 단순해진다.
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

  private static final String TOKEN_KEY_PREFIX = "rt:";
  private static final String USER_KEY_PREFIX = "rt:user:";

  private final StringRedisTemplate redis;

  @Override
  public void save(Long userId, String token, long ttlSeconds) {
    // 기존 토큰이 있으면 먼저 폐기 — 옛 refresh token으로는 더 이상 재발급이 안 된다
    String oldToken = redis.opsForValue().get(USER_KEY_PREFIX + userId);
    if (oldToken != null) {
      redis.delete(TOKEN_KEY_PREFIX + oldToken);
    }
    Duration ttl = Duration.ofSeconds(ttlSeconds);
    redis.opsForValue().set(TOKEN_KEY_PREFIX + token, String.valueOf(userId), ttl);
    redis.opsForValue().set(USER_KEY_PREFIX + userId, token, ttl);
  }

  @Override
  public Optional<Long> findUserId(String token) {
    String userId = redis.opsForValue().get(TOKEN_KEY_PREFIX + token);
    return Optional.ofNullable(userId).map(Long::valueOf);
  }

  @Override
  public void deleteByToken(String token) {
    String userId = redis.opsForValue().get(TOKEN_KEY_PREFIX + token);
    redis.delete(TOKEN_KEY_PREFIX + token);
    if (userId != null) {
      redis.delete(USER_KEY_PREFIX + userId);
    }
  }
}
```

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/token/TokenDenylist.java`** 와 **`RedisTokenDenylist.java`**

```java
// 단계 15: access token 즉시 폐기 목록(denylist).
// stateless JWT는 발급 후 만료까지 서버가 막을 수 없다는 한계(단계 2)를 해소한다 —
// 로그아웃 시 jti를 "남은 유효시간"만큼 등록하면, 토큰이 자연 만료되는 순간 키도 함께
// 사라지므로 목록이 무한히 쌓이지 않는다(자기정리).
public interface TokenDenylist {

  void deny(String jti, long remainingSeconds);

  boolean isDenied(String jti);
}

@Component
@RequiredArgsConstructor
public class RedisTokenDenylist implements TokenDenylist {

  private static final String KEY_PREFIX = "deny:";

  private final StringRedisTemplate redis;

  @Override
  public void deny(String jti, long remainingSeconds) {
    redis.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(remainingSeconds));
  }

  @Override
  public boolean isDenied(String jti) {
    return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
  }
}
```

**Redis 키 스키마 요약**

| 키 | 값 | TTL |
|---|---|---|
| `rt:{token}` | userId | 1209600s (14일) |
| `rt:user:{userId}` | token | 1209600s (14일) |
| `deny:{jti}` | `"1"` | 토큰 잔여 수명 |

## 2-8. refresh 쿠키 정책

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/auth/RefreshCookieFactory.java`** (전문)

```java
// 강의 포인트(단계 5): refresh token을 담는 Set-Cookie 헤더를 만든다.
// - HttpOnly: JS(document.cookie)가 못 읽어 XSS로 토큰을 탈취당하지 않는다.
// - Secure  : HTTPS에서만 전송. 로컬은 HTTP라 false. 운영에선 반드시 true.
// - SameSite: 크로스 사이트 요청에 쿠키 동봉을 제한해 CSRF 면을 줄인다(Strict이 가장 보수적).
// - Path    : 이 경로 하위 요청에만 쿠키가 전송돼 노출 면적을 좁힌다(/api/v1/auth).
@Component
public class RefreshCookieFactory {

  private final String name;
  private final boolean secure;
  private final String sameSite;
  private final String path;

  public RefreshCookieFactory(
      @Value("${app.refresh-cookie.name}") String name,
      @Value("${app.refresh-cookie.secure}") boolean secure,
      @Value("${app.refresh-cookie.same-site}") String sameSite,
      @Value("${app.refresh-cookie.path}") String path) {
    this.name = name;
    this.secure = secure;
    this.sameSite = sameSite;
    this.path = path;
  }

  public String cookieName() {
    return name;
  }

  // 발급용: refresh token을 담은 httpOnly 쿠키
  public ResponseCookie create(String refreshToken, long maxAgeSeconds) {
    return ResponseCookie.from(name, refreshToken)
        .httpOnly(true)
        .secure(secure)
        .sameSite(sameSite)
        .path(path)
        .maxAge(maxAgeSeconds)
        .build();
  }

  // 삭제용: 같은 name/path에 빈 값 + maxAge=0으로 클라이언트 쿠키를 즉시 만료시킨다
  public ResponseCookie expire() {
    return ResponseCookie.from(name, "")
        .httpOnly(true)
        .secure(secure)
        .sameSite(sameSite)
        .path(path)
        .maxAge(0)
        .build();
  }
}
```

설정값은 `app.refresh-cookie.*` 입니다. name은 `refreshToken`, sameSite는 `Strict`, **path는 `/api/v1/auth`**, secure는 `APP_REFRESH_COOKIE_SECURE` 환경변수(로컬 false, 서버 true)입니다.

path가 `/api/v1/auth` 로 제한되어 있습니다. 따라서 **WebSocket 핸드셰이크(`/ws` 등)에는 refresh 쿠키가 실리지 않습니다.** 채팅 클라이언트는 access token을 별도로 전달해야 합니다.

## 2-9. CustomUserDetails / CustomUserDetailsService (전문)

```java
// User 엔티티를 Spring Security가 이해하는 UserDetails로 감싸는 어댑터.
public class CustomUserDetails implements UserDetails {

  private final User user;

  public CustomUserDetails(User user) {
    this.user = user;
  }

  // 컨트롤러에서 @AuthenticationPrincipal로 받은 뒤 userId가 필요할 때 사용
  public Long getId() {
    return user.getId();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    // Spring Security의 hasRole("ADMIN")은 내부적으로 "ROLE_ADMIN" 권한을 찾는다
    return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
  }

  @Override
  public String getPassword() {
    return user.getPassword();
  }

  @Override
  public String getUsername() {
    return user.getUsername();
  }

  @Override public boolean isAccountNonExpired() { return true; }
  @Override public boolean isAccountNonLocked() { return true; }
  @Override public boolean isCredentialsNonExpired() { return true; }
  @Override public boolean isEnabled() { return true; }
}

// 강의 포인트: Spring Security 표준 진입점.
// AuthenticationManager(로그인)와 JwtAuthenticationFilter(매 요청)가 모두 이 한 곳에서 사용자를 로딩한다.
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

  private final UserRepository userRepository;

  @Override
  public UserDetails loadUserByUsername(String username) {
    return userRepository.findByUsername(username)
        .map(CustomUserDetails::new)
        .orElseThrow(() -> new UsernameNotFoundException(username));
  }
}
```

## 2-10. 401 / 403 응답 핸들러

```java
// 미인증 사용자가 보호 자원에 접근할 때(401). 우리 ErrorResponse JSON으로 응답한다.
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    writeError(response, ErrorCode.LOGIN_REQUIRED);
  }

  private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }
}

// 인증은 됐지만 권한이 부족할 때(403).
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }
}
```

## 2-11. OAuth2 소셜 로그인

**두 경로가 공존합니다.**

| 경로 | 구현 | 엔드포인트 |
|---|---|---|
| 단계 7 수동 구현 | `auth/oauth/KakaoOAuthController` | `GET /api/oauth/kakao/login`, `GET /api/oauth/kakao/callback` |
| 단계 8~9 표준 | `oauth2Login()` | `GET /oauth2/authorization/{kakao,google}`, `GET /login/oauth2/code/{kakao,google}` |

구글은 `openid` scope 때문에 OIDC 경로(`CustomOidcUserService`)를 탑니다. 카카오는 순수 OAuth2(`CustomOAuth2UserService`)입니다.

**`OAuth2LoginSuccessHandler`** 핵심부:

```java
  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request,
      HttpServletResponse response,
      Authentication authentication) throws IOException {
    // registrationId("kakao") → AuthProvider.KAKAO.
    OAuth2AuthenticationToken oauth2Token = (OAuth2AuthenticationToken) authentication;
    AuthProvider provider = AuthProvider.valueOf(
        oauth2Token.getAuthorizedClientRegistrationId().toUpperCase(Locale.ROOT));

    // user-name-attribute: id 덕분에 getName() = 카카오 회원번호 = providerId
    String providerId = authentication.getName();
    User user = userRepository.findByProviderAndProviderId(provider, providerId)
        .orElseThrow(() -> new IllegalStateException(
            "OAuth 로그인 직후 사용자가 없음: provider=" + provider + ", providerId=" + providerId));

    TokenPair tokens = authService.issueTokenPair(user);
    log.info("OAuth 로그인 성공(표준 경로): username={}", user.getUsername());

    // SPA 전환 처리: refresh 쿠키만 심고 SPA 루트("/")로 리다이렉트한다.
    // SPA는 로드 시 silent login(reissue)으로 이 쿠키를 사용해 access token을 얻는다.
    // access token을 URL에 싣지 않으므로 노출 위험도 없다.
    response.addHeader(HttpHeaders.SET_COOKIE,
        refreshCookieFactory.create(tokens.refreshToken(), tokens.refreshTokenValiditySeconds())
            .toString());
    response.sendRedirect("/");
  }
```

**`OAuth2LoginFailureHandler`**: 실패 시 `response.sendRedirect("/?error=" + errorCode.name())` 로 SPA 루트에 에러 코드를 실어 보냅니다.

**`CookieOAuth2AuthorizationRequestRepository`**: STATELESS 환경이라 세션 기본 저장소를 못 씁니다. 인가 요청을 쿠키 `oauthRequest`(httpOnly, SameSite=Lax, path=/, TTL 5분)에 JSON + Base64url로 보관합니다. JDK 직렬화를 피해 insecure deserialization(CWE-502)을 막았고, OIDC nonce 필드를 명시적으로 보존합니다(누락 시 nonce 검증이 무음 스킵됨).

```java
  record StoredRequest(
      String state,
      String authorizationUri,
      String clientId,
      String redirectUri,
      Set<String> scopes,
      String registrationId,
      // 단계 9 nonce 보강: OIDC일 때 라이브러리가 attribute로 실어 나르는 난수 원본.
      String nonce
  ) {
  }
```

**`CustomOAuth2UserService.upsertUser`** — 제공자별 분기가 한 곳에 모여 있습니다.

```java
  // find-or-create — (provider, providerId)로 찾고 없으면 가입
  User upsertUser(String registrationId, Map<String, Object> attributes) {
    AuthProvider provider = AuthProvider.valueOf(registrationId.toUpperCase(Locale.ROOT));
    OAuth2UserInfo userInfo = extractUserInfo(provider, attributes);
    return userRepository.findByProviderAndProviderId(provider, userInfo.providerId())
        .orElseGet(() -> createUser(provider, registrationId, userInfo));
  }

  // 카카오: 중첩 JSON (id / kakao_account.email / kakao_account.profile.nickname)
  // 구글:   평면 JSON (sub / email / name)
  private OAuth2UserInfo extractUserInfo(AuthProvider provider, Map<String, Object> attributes) {
    return switch (provider) {
      case KAKAO -> new OAuth2UserInfo(
          String.valueOf(attributes.get("id")),
          kakaoEmail(attributes),
          kakaoNickname(attributes));
      case GOOGLE -> new OAuth2UserInfo(
          (String) attributes.get("sub"),
          (String) attributes.get("email"),
          (String) attributes.get("name"));
      case LOCAL -> throw new IllegalStateException("LOCAL은 소셜 제공자가 아니다");
    };
  }

  private User createUser(AuthProvider provider, String registrationId, OAuth2UserInfo userInfo) {
    // kakao_4614.., google_1076.. — registration ID가 접두사라 제공자 간 충돌이 없다
    String username = registrationId + "_" + userInfo.providerId();

    // 이메일 미동의(null)이거나 기존 계정과 겹치면 대체 이메일
    String email = userInfo.email();
    if (email == null || userRepository.existsByEmail(email)) {
      email = username + "@" + registrationId + ".local";
    }

    // 소셜 사용자는 password 로그인 불가 — 아무도 모르는 랜덤 값을 해시해 저장
    String password = passwordEncoder.encode(UUID.randomUUID().toString());

    User user = userRepository.save(
        new User(username, email, password, Role.USER, provider, userInfo.providerId()));
    userProfileRepository.save(new UserProfile(
        user, uniqueNickname(userInfo.nickname(), username, userInfo.providerId()), null));
    log.info("소셜 신규 사용자 가입: username={}", username);
    return user;
  }
```

**`CustomOidcUserService`** — 상속이 아니라 위임(composition)입니다.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final CustomOAuth2UserService customOAuth2UserService;

  private OAuth2UserService<OidcUserRequest, OidcUser> delegate = new OidcUserService();

  // 테스트 전용 — 실제 구글 호출 없이 delegate를 stub으로 교체한다
  void setDelegate(OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
    this.delegate = delegate;
  }

  @Override
  @Transactional
  public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
    // id_token 서명 검증과 (scope에 따라) userinfo 조회는 표준 구현이 끝낸다
    OidcUser oidcUser = delegate.loadUser(userRequest);
    String registrationId = userRequest.getClientRegistration().getRegistrationId();
    // getAttributes() = id_token claims(+userinfo) — 평면 sub/email/name이라 GOOGLE 분기 재사용
    customOAuth2UserService.upsertUser(registrationId, oidcUser.getAttributes());
    log.debug("OIDC 사용자 로딩 완료: registrationId={}", registrationId);
    return oidcUser;
  }
}
```

**단계 7 수동 카카오 경로** (`auth/oauth/KakaoOAuthController`)는 아직 살아 있습니다. state 쿠키 `oauthState`(httpOnly, SameSite=**Lax**, path=`/api/oauth/kakao`, TTL 5분)를 직접 만들고 콜백에서 대조합니다. 이 경로만 access token을 응답 **본문**으로 내려줍니다.

`KakaoOAuthProperties` 는 `@ConfigurationProperties(prefix = "app.oauth.kakao")` + `@PostConstruct validate()` 로 fail-fast 합니다. 미해석 placeholder(`"${KAKAO_SECRET}"`)가 그대로 통과하는 것을 막기 위함이며, **이 때문에 `.env` 의 카카오 키가 비어 있으면 앱이 기동하지 않습니다.**

## 2-12. 필터 체인 실제 순서

`csrf`/`formLogin`/`httpBasic` 을 disable 했으므로 `CsrfFilter`, `UsernamePasswordAuthenticationFilter`, `BasicAuthenticationFilter` 가 체인에서 빠집니다. 실제 순서는 다음과 같습니다.

```
SecurityContextHolderFilter
  → HeaderWriterFilter
  → JwtAuthenticationFilter        (addFilterBefore로 삽입)
  → (oauth2Login 관련 필터들)
  → AuthorizationFilter
  → DispatcherServlet
```


---

# 3. User 도메인

**`/Users/eunbumkim/Documents/practice/java/board/src/main/java/com/example/board/user/User.java`** (전문)

```java
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(
    name = "uk_users_provider_provider_id", columnNames = {"provider", "provider_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 50)
  private String username;

  @Column(nullable = false, unique = true, length = 100)
  private String email;

  // BCrypt 해시가 저장된다. 평문 저장 절대 금지.
  @Column(nullable = false)
  private String password;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role;

  // 단계 7: 가입 경로. 자체 가입은 LOCAL, 소셜 로그인은 KAKAO/GOOGLE.
  // 단계 8(구글 추가): MySQL 네이티브 ENUM 컬럼이면 제공자를 추가할 때마다 ALTER가 필요하므로
  // VARCHAR로 저장한다 — 기존 DB는 1회 전환 필요:
  //   ALTER TABLE users MODIFY provider VARCHAR(20) NOT NULL;
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 20)
  private AuthProvider provider;

  // 소셜 제공자가 발급한 회원 고유번호(카카오 회원번호). LOCAL 사용자는 null.
  @Column(name = "provider_id", length = 100)
  private String providerId;

  public User(String username, String email, String password, Role role) {
    this(username, email, password, role, AuthProvider.LOCAL, null);
  }

  public User(
      String username, String email, String password, Role role,
      AuthProvider provider, String providerId) {
    this.username = username;
    this.email = email;
    this.password = password;
    this.role = role;
    this.provider = provider;
    this.providerId = providerId;
  }
}
```

**필드 타입 요약**

| 필드 | 타입 | 제약 |
|---|---|---|
| `id` | `Long` | PK, IDENTITY (MySQL AUTO_INCREMENT) |
| `username` | `String` | NOT NULL, UNIQUE, 50 |
| `email` | `String` | NOT NULL, UNIQUE, 100 |
| `password` | `String` | NOT NULL, BCrypt 해시 |
| `role` | `Role` enum | NOT NULL, VARCHAR(20), `USER` / `ADMIN` |
| `provider` | `AuthProvider` enum | NOT NULL, VARCHAR(20), `LOCAL` / `KAKAO` / `GOOGLE` |
| `providerId` | `String` | NULL 허용, 100 |
| `createdAt`, `updatedAt` | `LocalDateTime` | `BaseTimeEntity` 상속 |

복합 UNIQUE 제약 `uk_users_provider_provider_id (provider, provider_id)` 가 소셜 사용자의 식별자입니다.

**nickname은 User가 아니라 `UserProfile` 에 있습니다.** 채팅 화면에 표시할 이름은 `user_profiles.nickname` 입니다.

**`Role.java` / `AuthProvider.java`**

```java
public enum Role {
  USER, ADMIN
}

// 단계 7: 이 사용자가 어떤 경로로 가입했는지 구분한다.
// 이름 규칙: yaml의 registration ID("kakao", "google")를 대문자로 바꾼 것과 일치해야 한다
public enum AuthProvider {
  LOCAL,
  KAKAO,
  GOOGLE   // 단계 8 확장
}
```

**`UserRepository.java`** (전문)

```java
public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByUsername(String username);

  // 단계 7: 소셜 사용자는 (provider, providerId) 쌍이 유일한 식별자다
  Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

  boolean existsByUsername(String username);

  boolean existsByEmail(String email);
}
```

**`UserProfile.java`** (핵심부)

```java
// 인증 정보(User)와 부가 정보(UserProfile)를 분리하면
// 보안 민감 데이터와 자주 바뀌는 데이터의 관심사가 나뉜다
@Entity
@Table(name = "user_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  private User user;

  @Column(nullable = false, unique = true, length = 50)
  private String nickname;

  @Column(length = 500)
  private String bio;

  @Column(length = 20)
  private String phoneNumber;

  private LocalDate birthDate;

  @Column(length = 500)
  private String profileImageUrl;
}
```

**`UserProfileRepository.java`**

```java
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

  // ProfileResponse가 username/email을 함께 내려주므로
  // @EntityGraph로 User를 한 번에 조회해 LAZY 프록시 추가 쿼리를 막는다
  @EntityGraph(attributePaths = "user")
  Optional<UserProfile> findByUserId(Long userId);

  boolean existsByNickname(String nickname);

  boolean existsByNicknameAndUserIdNot(String nickname, Long userId);
}
```

**`BaseTimeEntity.java`** (전문)

```java
// 모든 Entity가 공통으로 갖는 생성/수정 시각. JPA Auditing이 자동으로 채워준다.
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

  @CreatedDate
  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @LastModifiedDate
  @Column(nullable = false)
  private LocalDateTime updatedAt;
}
```

Auditing 시각은 마이크로초로 절단됩니다. keyset 커서 중복 버그 때문입니다.

```java
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

  // 단계 16: Auditing 시각을 DB 저장 정밀도(마이크로초)로 절단한다.
  // Linux JDK의 LocalDateTime.now()는 나노초까지 주지만 DATETIME(6)은 마이크로초라,
  // 절단 없이는 "메모리 엔티티의 createdAt ≠ DB에 저장된 createdAt"가 된다.
  @Bean
  public DateTimeProvider auditingDateTimeProvider() {
    return () -> Optional.of(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
  }
}
```

**전체 테이블 목록** (`@Table` 선언 기준)

| 테이블 | 엔티티 | 비고 |
|---|---|---|
| `users` | `User` | UNIQUE `(provider, provider_id)` |
| `user_profiles` | `UserProfile` | `user_id` UNIQUE (1:1) |
| `boards` | `Board` | `name` UNIQUE |
| `posts` | `Post` | 복합 인덱스 다수 (단계 16), FULLTEXT ngram (단계 17) |
| `post_images` | `PostImage` | |
| `comments` | `Comment` | 자기참조 parent, soft delete |
| `notifications` | `Notification` | 인덱스 `(recipient_id, created_at)` |
| `post_reactions` | `PostReaction` | UNIQUE 제약 |
| `comment_reactions` | `CommentReaction` | UNIQUE 제약 |

DDL은 `ddl-auto: update` 로 Hibernate가 만듭니다. 별도 마이그레이션 도구(Flyway/Liquibase)는 없습니다.

**ADMIN 시드 계정** (`global/config/DataInitializer.java`, `@Profile("!prod")`)

```java
    User admin = new User(
        adminUsername,                       // app.admin.username:admin
        "admin@example.com",
        passwordEncoder.encode(adminPassword), // app.admin.password:admin1234
        Role.ADMIN);
    userRepository.save(admin);
    userProfileRepository.save(new UserProfile(admin, "관리자", "강의용 관리자 계정"));
```

---

# 4. DB / 인프라 설정

## 4-1. `/Users/eunbumkim/Documents/practice/java/board/src/main/resources/application.yaml` (전문, secret 마스킹)

```yaml
server:
  port: 8090
  # 리버스 프록시(Nginx) 뒤에서 실행될 때, X-Forwarded-* 헤더로 원래 요청의 host/proto를
  # 복원한다. OAuth2 redirect-uri({baseUrl})가 내부 board-app:8090 이 아니라 실제 공개 주소로
  # 계산되게 하는 핵심 설정.
  forward-headers-strategy: framework

spring:
  application:
    name: board
  config:
    # .env 파일을 properties 형식으로 로드한다 (optional: 없어도 기동은 됨).
    # 같은 이름의 OS 환경변수가 있으면 그쪽이 우선한다.
    import: optional:file:.env[.properties]

  # 단계 8: OAuth 표준화 — oauth2-client의 표준 프로퍼티.
  security:
    oauth2:
      client:
        registration:
          kakao:
            client-id: ${KAKAO_REST_API}
            client-secret: ${KAKAO_SECRET}
            # {baseUrl}이 실행 환경에 맞게 치환된다 → http://localhost:8090/login/oauth2/code/kakao
            redirect-uri: "{baseUrl}/login/oauth2/code/kakao"
            authorization-grant-type: authorization_code
            # 카카오 token endpoint는 Basic 헤더가 아니라 form 본문의 client_id/secret을 요구한다
            client-authentication-method: client_secret_post
            scope: profile_nickname
          # 단계 9: scope에 openid를 추가 → 구글 로그인이 OIDC 경로로 전환된다.
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid,profile,email
        provider:
          kakao:
            authorization-uri: https://kauth.kakao.com/oauth/authorize
            token-uri: https://kauth.kakao.com/oauth/token
            user-info-uri: https://kapi.kakao.com/v2/user/me
            # 사용자 응답에서 "이 사람의 이름"으로 쓸 최상위 속성 → authentication.getName() = 카카오 회원번호
            user-name-attribute: id
  # 단계 15: Redis — refresh token 저장소(TTL) + access token denylist.
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:board}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:****마스킹****}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      # 강의 편의상 update 사용. 운영에서는 validate + Flyway/Liquibase 권장
      ddl-auto: update
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
        # 단계 11: LAZY 연관을 개별 SELECT가 아니라 IN 쿼리로 묶어 로딩(N+1 완화).
        default_batch_fetch_size: 100
  # 단계 10: multipart 업로드 크기 제한 — 초과 시 MaxUploadSizeExceededException(413)로 응답
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 20MB

jwt:
  secret: ${JWT_SECRET:****마스킹****}  # Base64, 로컬 강의용 기본값 (HS256 최소 256비트)
  access-token-validity-seconds: 3600  # 1시간
  refresh-token-validity-seconds: 1209600  # 14일

# refresh token을 담는 httpOnly 쿠키 옵션 (단계 5)
app:
  # 단계 10: 업로드 파일 저장 루트. 상대경로면 앱 실행 디렉터리 기준.
  upload:
    dir: ${APP_UPLOAD_DIR:./uploads}

  refresh-cookie:
    name: refreshToken
    # 운영(HTTPS)에선 반드시 true. 로컬은 HTTP라 true면 쿠키가 전송되지 않아 테스트가 깨진다.
    secure: ${APP_REFRESH_COOKIE_SECURE:false}
    same-site: Strict
    # reissue/logout 모두 이 경로 아래라 쿠키가 전송된다. 노출 면적을 auth 경로로 좁힌다.
    path: /api/v1/auth

  # 단계 7: 카카오 OAuth2 (authorization code 수동 구현)
  oauth:
    kakao:
      appkey: ${KAKAO_REST_API}
      # Client Secret — 진짜 비밀값. yaml에 기본값을 두지 않는다(.env 또는 환경변수 필수).
      secret: ${KAKAO_SECRET}
      callback: ${KAKAO_CALLBACK:http://localhost:8090/api/oauth/kakao/callback}
      authorize-uri: https://kauth.kakao.com/oauth/authorize
      token-uri: https://kauth.kakao.com/oauth/token
      user-info-uri: https://kapi.kakao.com/v2/user/me

logging:
  level:
    org.hibernate.SQL: debug
    org.springframework.security.web.FilterChainProxy: DEBUG
    # 단계 9: OAuth2/OIDC 처리 흐름의 debug 로그를 보기 위함
    com.example.board.auth.oauth2: debug
```

`jwt.secret` 과 `DB_PASSWORD` 기본값은 마스킹했습니다. 원문 yaml에는 로컬 강의용 Base64 기본값이 하드코딩되어 있습니다. 채팅 서버와 secret을 공유하려면 `JWT_SECRET` 환경변수로 통일하는 편이 안전합니다.

## 4-2. `/Users/eunbumkim/Documents/practice/java/board/src/test/resources/application.yaml` (전문)

```yaml
spring:
  # 단계 8: oauth2Login() 구성은 registration이 하나도 없으면 컨텍스트 기동에 실패한다 — 더미 등록
  security:
    oauth2:
      client:
        registration:
          kakao:
            client-id: test-appkey
            client-secret: test-secret
            redirect-uri: "{baseUrl}/login/oauth2/code/kakao"
            authorization-grant-type: authorization_code
            client-authentication-method: client_secret_post
            scope: profile_nickname
        provider:
          kakao:
            authorization-uri: https://kauth.kakao.com/oauth/authorize
            token-uri: https://kauth.kakao.com/oauth/token
            user-info-uri: https://kapi.kakao.com/v2/user/me
            user-name-attribute: id
  datasource:
    url: jdbc:h2:mem:board;MODE=MySQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false

jwt:
  secret: ****마스킹****
  access-token-validity-seconds: 3600
  refresh-token-validity-seconds: 1209600

app:
  upload:
    dir: ${java.io.tmpdir}/board-test-uploads

  refresh-cookie:
    name: refreshToken
    secure: false
    same-site: Strict
    path: /api/v1/auth

  # 테스트는 KakaoOAuthClient를 mock하므로 실제로 호출되지 않는 더미 값
  oauth:
    kakao:
      appkey: test-appkey
      secret: test-secret
      callback: http://localhost:8090/api/oauth/kakao/callback
      authorize-uri: https://kauth.kakao.com/oauth/authorize
      token-uri: https://kauth.kakao.com/oauth/token
      user-info-uri: https://kapi.kakao.com/v2/user/me
```

테스트는 H2 인메모리(MySQL 호환 모드)를 씁니다. 외부 의존이 없어 CI에서 그대로 돌아갑니다.

Redis는 테스트에서 InMemory로 대체됩니다.

**`/Users/eunbumkim/Documents/practice/java/board/src/test/java/com/example/board/support/TestTokenStoreConfig.java`** (전문)

```java
// 단계 15: 모든 @SpringBootTest 컨텍스트에서 Redis 구현을 InMemory로 대체한다.
// 테스트 클래스패스의 @Configuration은 BoardApplication 컴포넌트 스캔 범위(com.example.board)에
// 있어 자동으로 적용된다 — @Primary가 Redis 빈 대신 이 빈들을 주입시킨다.
@Configuration
public class TestTokenStoreConfig {

  @Bean
  @Primary
  public RefreshTokenStore inMemoryRefreshTokenStore() {
    return new InMemoryRefreshTokenStore();
  }

  @Bean
  @Primary
  public TokenDenylist inMemoryTokenDenylist() {
    return new InMemoryTokenDenylist();
  }
}
```

`InMemoryRefreshTokenStore` 는 `ConcurrentHashMap` 2개로 양방향 매핑을 흉내내며 `clear()` 를 제공합니다. `@Transactional` 롤백이 인메모리 Map을 되돌리지 못하므로 `@BeforeEach` 에서 비워야 합니다. `InMemoryTokenDenylist` 는 `ConcurrentHashMap.newKeySet()` 으로 membership만 흉내내고 TTL은 강제하지 않습니다.

## 4-3. 사용 인프라 요약

| 구성요소 | 값 |
|---|---|
| DB | **MySQL 8.0.33** (컨테이너 `mysql-8`, DB명 `board`, timezone `+09:00`) |
| 테스트 DB | H2 인메모리 (MODE=MySQL) |
| Redis | **사용함**. `redis:7-alpine`, 컨테이너 `board-redis`, 용도는 refresh 저장소 + access denylist |
| Redis 옵션 | `--maxmemory 64mb --maxmemory-policy noeviction` (토큰 축출 = 강제 로그아웃이라 금지), AOF 없음 |
| JPA | `ddl-auto: update`, `open-in-view: false`, `default_batch_fetch_size: 100`, Auditing 활성 |
| 서버 포트 | 8090 |
| 프록시 대응 | `forward-headers-strategy: framework` |

## 4-4. 프로파일 구성

`application-*.yaml` 프로파일 파일은 **없습니다.** 단일 yaml에 환경변수 placeholder만 씁니다. 유일한 프로파일 사용처는 `DataInitializer` 의 `@Profile("!prod")` 입니다. 실제 배포에서도 `prod` 프로파일은 활성화하지 않으므로 ADMIN 시드가 서버에서도 돕니다.

기타 설정 클래스입니다.

```java
// WebConfig — Page 직렬화 + 업로드 이미지 정적 서빙
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

  private final String uploadDir;

  public WebConfig(@Value("${app.upload.dir}") String uploadDir) {
    this.uploadDir = uploadDir;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
    String location = root.toUri().toString();
    registry.addResourceHandler("/images/**")
        .addResourceLocations(location);
  }
}

// RestClientConfig — 외부 API 호출용 RestClient 빈
@Configuration
public class RestClientConfig {

  @Bean
  public RestClient restClient(RestClient.Builder builder) {
    return builder.build();
  }
}
```

`BoardApplication.java`

```java
// 단계 7: @ConfigurationPropertiesScan — KakaoOAuthProperties 같은 record 기반 프로퍼티 바인딩을 활성화한다
@SpringBootApplication
@ConfigurationPropertiesScan
public class BoardApplication {

	public static void main(String[] args) {
		SpringApplication.run(BoardApplication.class, args);
	}

}
```

---

# 5. 패키지 구조 / 공통 규약

## 5-1. `src/main/java` 전체 트리

```
src/main/java/com/example/board/
├── BoardApplication.java                     @SpringBootApplication + @ConfigurationPropertiesScan
│
├── auth/                                     인증 전반
│   ├── AuthController.java                   /api/v1/auth/** 진입점
│   ├── AuthService.java                      signup/login/issueTokenPair/reissue/logout
│   ├── CustomUserDetails.java                User → UserDetails 어댑터 (getId() 제공)
│   ├── CustomUserDetailsService.java         loadUserByUsername (표준 진입점)
│   ├── RefreshCookieFactory.java             Set-Cookie 생성/만료
│   ├── dto/
│   │   ├── LoginRequest.java   SignupRequest.java
│   │   └── TokenPair.java      TokenResponse.java
│   ├── jwt/
│   │   ├── JwtTokenProvider.java             토큰 발급·검증·jti·잔여수명
│   │   └── JwtAuthenticationFilter.java      Bearer → SecurityContext
│   ├── token/
│   │   ├── RefreshTokenStore.java            인터페이스
│   │   ├── RedisRefreshTokenStore.java       rt:{token}, rt:user:{userId}
│   │   ├── TokenDenylist.java                인터페이스
│   │   └── RedisTokenDenylist.java           deny:{jti}
│   ├── oauth/                                단계 7 수동 카카오 구현 (현재도 동작)
│   │   ├── KakaoOAuthController.java         /api/oauth/kakao/{login,callback}
│   │   ├── KakaoOAuthClient.java             토큰 교환 + userinfo 호출 경계
│   │   ├── KakaoOAuthService.java            find-or-create + 토큰 발급
│   │   ├── KakaoOAuthProperties.java         @ConfigurationProperties + fail-fast
│   │   └── dto/ KakaoTokenResponse.java KakaoUserResponse.java
│   └── oauth2/                               단계 8~9 표준 oauth2-client
│       ├── CookieOAuth2AuthorizationRequestRepository.java   STATELESS용 쿠키 저장소
│       ├── CustomOAuth2UserService.java      카카오(순수 OAuth2) 사용자 로딩 + upsert
│       ├── CustomOidcUserService.java        구글(OIDC) 사용자 로딩 (위임 방식)
│       ├── OAuth2LoginSuccessHandler.java    우리 토큰 발급 + refresh 쿠키 + "/" 리다이렉트
│       └── OAuth2LoginFailureHandler.java    "/?error=CODE" 리다이렉트
│
├── user/                                     User, Role, AuthProvider, UserRepository, dto/UserResponse
├── profile/                                  UserProfile, ProfileController/Service/Repository, dto/
├── board/                                    Board, BoardController/Service/Repository, dto/
├── post/                                     Post, PostImage, PostSecurity(소유권 SpEL 빈),
│                                             PostController/Service/Repository, dto/ (Cursor 포함)
├── comment/                                  Comment, CommentSecurity, CommentController/Service/Repository, dto/
├── reaction/                                 PostReaction, CommentReaction, ReactionType,
│                                             ReactionController/Service, 집계 projection, dto/
├── notification/                             Notification, NotificationType, CommentCreatedEvent,
│                                             NotificationEventListener, Controller/Service/Repository, dto/
└── global/
    ├── config/
    │   ├── SecurityConfig.java               필터 체인·인가 규칙·oauth2Login
    │   ├── WebConfig.java                    Page 직렬화 + /images/** 정적 서빙
    │   ├── JpaAuditingConfig.java            Auditing + 마이크로초 절단
    │   ├── RestClientConfig.java             RestClient 빈
    │   ├── DataInitializer.java              ADMIN 시드 (@Profile("!prod"))
    │   ├── RestAuthenticationEntryPoint.java 401 JSON
    │   └── RestAccessDeniedHandler.java      403 JSON
    ├── entity/BaseTimeEntity.java            createdAt / updatedAt
    ├── exception/
    │   ├── ErrorCode.java                    (HttpStatus, message) enum — 상태의 단일 권위
    │   ├── ErrorResponse.java                code/message/timestamp/errors
    │   ├── GlobalExceptionHandler.java       @RestControllerAdvice, 핸들러 12개
    │   ├── BusinessException.java            루트 (ErrorCode 보유)
    │   └── NotFound/Duplicate/Unauthorized/ForbiddenException.java
    └── storage/FileStorageService.java       업로드 파일 저장 경계
```

**도메인별 수직 분할 구조입니다.** 컨트롤러/서비스/리포지토리/엔티티/DTO가 한 패키지에 모여 있습니다. 채팅 도메인도 `chat/` 패키지 하나로 같은 규칙을 따르면 일관됩니다.

## 5-2. 공통 응답 포맷

**`ApiResponse` 같은 성공 래퍼는 없습니다.** 성공 응답은 DTO를 그대로 반환합니다. 실패 응답만 `ErrorResponse` 로 통일합니다.

```java
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

응답 예시는 다음과 같은 모양입니다.

```json
{
  "code": "LOGIN_REQUIRED",
  "message": "로그인이 필요합니다.",
  "timestamp": "2026-09-12T01:23:45.123456"
}
```

`errors` 는 `@JsonInclude(NON_NULL)` 이라 검증 실패 응답에만 나타납니다.

## 5-3. 예외 처리 전략

**예외 계층**

```java
@Getter
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
```

하위에 `NotFoundException`, `DuplicateException`, `UnauthorizedException`, `ForbiddenException` 4종이 있습니다. 전부 생성자만 위임하는 빈 클래스입니다. HTTP 상태는 전적으로 `ErrorCode` 가 정합니다.

**`ErrorCode.java` 전문**

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "프로필을 찾을 수 없습니다."),
  BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "게시판을 찾을 수 없습니다."),
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
  COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
  // 단계 12: 알림 — 남의 알림 존재 유출을 막기 위해 소유 검증 실패도 이 코드(404)로 통일한다(열거 방어).
  NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
  // 단계 7에서 발견: 매핑 없는 URL이 500으로 새던 것을 404로 교정
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

  DUPLICATE_USERNAME(HttpStatus.CONFLICT, "이미 사용 중인 username입니다."),
  DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 email입니다."),
  DUPLICATE_BOARD_NAME(HttpStatus.CONFLICT, "이미 존재하는 게시판 이름입니다."),
  NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 nickname입니다."),

  // 단계 6에서 작성자 거부가 메서드 보안으로 이동하며 ACCESS_DENIED로 통합됨. 미사용이나 보존.
  POST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "게시글에 대한 권한이 없습니다."),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

  // 단계 7: 카카오 OAuth2 — 내부 사유는 서버 로그로
  OAUTH_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다."),
  INVALID_OAUTH_STATE(HttpStatus.UNAUTHORIZED, "OAuth state 검증에 실패했습니다. 처음부터 다시 시도하세요."),

  LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "username 또는 password가 올바르지 않습니다."),
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh token입니다."),
  // 단계 15 처리에 의해 미사용 — TTL 만료 시 키가 사라져 "없음=무효(INVALID)"로 단일화됨.
  EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 refresh token입니다. 다시 로그인하세요."),
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
  // 단계 17: ngram_token_size=2라 1글자 검색어는 항상 0건이다. 명시적으로 거부한다.
  SEARCH_QUERY_TOO_SHORT(HttpStatus.BAD_REQUEST, "검색어는 2글자 이상이어야 합니다."),
  // 단계 11: 댓글/대댓글 — 1단계 깊이 불변식과 삭제된 댓글에 대한 제약
  CANNOT_REPLY_TO_REPLY(HttpStatus.BAD_REQUEST, "대댓글에는 답글을 달 수 없습니다."),
  CANNOT_REPLY_TO_DELETED(HttpStatus.BAD_REQUEST, "삭제된 댓글에는 답글을 달 수 없습니다."),
  CANNOT_EDIT_DELETED(HttpStatus.BAD_REQUEST, "삭제된 댓글은 수정할 수 없습니다."),
  COMMENT_POST_MISMATCH(HttpStatus.BAD_REQUEST, "부모 댓글이 해당 게시글의 댓글이 아닙니다."),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문(JSON)을 읽을 수 없습니다."),
  TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "요청 값의 타입이 올바르지 않습니다."),
  MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 미디어 타입입니다."),

  // 단계 10: 파일 업로드
  INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "허용되지 않는 파일 형식입니다."),
  FILE_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "첨부 가능한 이미지 개수를 초과했습니다."),
  FILE_SIZE_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 가능한 파일 크기를 초과했습니다."),
  FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드에 실패했습니다."),

  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String message;
}
```

**`GlobalExceptionHandler`** 는 `@RestControllerAdvice` 로 핸들러 12개를 둡니다.

| 핸들러 대상 예외 | 응답 |
|---|---|
| `BusinessException` | ErrorCode가 정한 상태 (4종 하위 전부 흡수) |
| `MethodArgumentNotValidException` | 400 `INVALID_INPUT` + 필드별 errors 배열 |
| `HttpMessageNotReadableException` | 400 `MALFORMED_REQUEST` |
| `MethodArgumentTypeMismatchException` | 400 `TYPE_MISMATCH` |
| `MissingServletRequestParameterException` | 400 `MISSING_PARAMETER` |
| `HttpRequestMethodNotSupportedException` | 405 `METHOD_NOT_ALLOWED` |
| `HttpMediaTypeNotSupportedException` | 415 `UNSUPPORTED_MEDIA_TYPE` |
| `MaxUploadSizeExceededException` | 413 `FILE_SIZE_EXCEEDED` |
| `MultipartException` | 400 `INVALID_INPUT` |
| `AccessDeniedException` | 403 `ACCESS_DENIED` (`@PreAuthorize` 거부가 여기로 온다) |
| `NoResourceFoundException` | 404 `RESOURCE_NOT_FOUND` |
| `Exception` | 500 `INTERNAL_ERROR` (스택트레이스는 `log.error` 로 서버에만) |

대표 핸들러 원문입니다.

```java
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

  // 단계 6: @PreAuthorize 거부는 AuthorizationDeniedException(AccessDeniedException의 하위)을
  // 던지므로 RestAccessDeniedHandler(필터 단계)가 아닌 여기서 잡힌다. 403으로 일원화한다.
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
    log.warn("Access denied: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.ACCESS_DENIED.getStatus())
        .body(ErrorResponse.of(ErrorCode.ACCESS_DENIED));
  }

  // 예상하지 못한 예외는 상세를 숨기고 로그만 남긴다 (보안상 내부 정보 노출 금지)
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    log.error("Unexpected exception", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
        .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
  }
```

**주의**: `@RestControllerAdvice` 는 서블릿 필터와 STOMP 메시지 처리 경로를 커버하지 않습니다. board는 필터용으로 `HandlerExceptionResolver` 위임을 씁니다. 채팅 서버는 `@MessageExceptionHandler` 를 별도로 두어야 합니다.

## 5-4. 전체 엔드포인트 표

| 경로 | 메서드 | 인가 |
|---|---|---|
| `/api/v1/auth/signup` | POST | permitAll |
| `/api/v1/auth/login` | POST | permitAll |
| `/api/v1/auth/reissue` | POST | permitAll |
| `/api/v1/auth/logout` | POST | permitAll (stateless) |
| `/api/oauth/kakao/login` | GET | permitAll (단계 7 수동) |
| `/api/oauth/kakao/callback` | GET | permitAll (단계 7 수동) |
| `/oauth2/authorization/{kakao,google}` | GET | oauth2Login 표준 |
| `/login/oauth2/code/{kakao,google}` | GET | oauth2Login 표준 |
| `/api/v1/boards` | GET / POST | GET 공개, POST `hasRole('ADMIN')` |
| `/api/v1/boards/{id}` | GET / PUT / DELETE | GET 공개, 쓰기 ADMIN |
| `/api/v1/boards/{boardId}/posts` | GET / POST(multipart) | GET 공개, POST 인증 |
| `/api/v1/boards/{boardId}/posts/cursor` | GET | 공개 (keyset 페이지네이션) |
| `/api/v1/boards/{boardId}/posts/search` | GET | 공개 (FULLTEXT ngram) |
| `/api/v1/posts/{id}` | GET / PUT(multipart) / DELETE | GET 공개, 수정·삭제는 작성자만 (`@postSecurity`) |
| `/api/v1/posts/{postId}/comments` | GET / POST | GET 공개, POST 인증 |
| `/api/v1/comments/{id}` | PUT / DELETE | 작성자만 (`@commentSecurity`) |
| `/api/v1/posts/{postId}/reactions` | POST | 인증 |
| `/api/v1/comments/{commentId}/reactions` | POST | 인증 |
| `/api/v1/notifications` | GET | 인증 (본인 것만) |
| `/api/v1/notifications/unread-count` | GET | 인증 |
| `/api/v1/notifications/{id}/read` | PATCH | 인증 (소유 아니면 404) |
| `/api/v1/notifications/read-all` | PATCH | 인증 |
| `/api/v1/profiles/me` | GET / PUT | 인증 |
| `/api/v1/profiles/{userId}` | GET | 공개 |
| `/images/**` | GET | 공개 (정적 서빙) |

## 5-5. 알림 도메인 (채팅 실시간화의 직접 선행 지점)

`Notification` 엔티티는 완성된 문구가 아니라 메타데이터만 저장합니다.

```java
@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notifications_recipient_created", columnList = "recipient_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 알림을 받는 사람. LAZY — 인가 필터(isOwnedBy)로만 쓰이고 응답에 노출하지 않는다.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recipient_id", nullable = false)
  private User recipient;

  // 알림을 유발한 사람(댓글 작성자).
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_id", nullable = false)
  private User actor;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private NotificationType type;

  // 링크용(느슨한 결합) — Post/Comment 엔티티 참조 대신 id만 저장한다.
  @Column(name = "post_id", nullable = false)
  private Long postId;

  @Column(name = "comment_id")
  private Long commentId;

  // 예약어 논란을 피하려 컬럼명은 is_read. getter는 isRead()로 노출된다.
  @Column(name = "is_read", nullable = false)
  private boolean read;

  public void markAsRead() {
    this.read = true;
  }

  // 인가 필터 — 남의 알림 id를 알아도 이 검사에서 걸린다. FK만 비교해 LAZY 로딩을 유발하지 않는다.
  public boolean isOwnedBy(Long userId) {
    return recipient.getId().equals(userId);
  }
}
```

이벤트 발행/수신 구조는 `CommentCreatedEvent` + `NotificationEventListener` 이며, `@TransactionalEventListener(phase = AFTER_COMMIT)` 와 `@Transactional(propagation = REQUIRES_NEW)` 조합입니다. 현재 UX는 폴링(`GET /api/v1/notifications/unread-count`)입니다. 실시간 푸시는 의도적으로 범위 밖으로 남겨져 있습니다.


---

# 6. 배포

## 6-1. `/Users/eunbumkim/Documents/practice/java/board/Dockerfile` (전문)

```dockerfile
# syntax=docker/dockerfile:1

# ── 1) build stage ─────────────────────────────────────────────────────────
# Gradle 래퍼로 실행 가능한 fat jar(bootJar)만 만든다. 로컬에 gradle/JDK가 없어도
# 이미지 안에서 완결적으로 빌드된다. 테스트는 H2로 도는데, 이미지 빌드는 배포 산출물
# 생성이 목적이므로 여기선 `-x test`로 건너뛴다(테스트는 verify-loop/CI에서 별도 수행).
# AS build: 이 스테이지에 "build"라는 이름을 붙인다. 나중 런타임 스테이지에서
# COPY --from=build 로 여기서 만든 jar만 가져오고, JDK·Gradle·소스는 버린다(멀티스테이지의 핵심).
FROM eclipse-temurin:21-jdk AS build

# WORKDIR: 이후 명령(COPY/RUN)의 기준 작업 디렉터리. 없으면 새로 만든다.
WORKDIR /workspace

# ── 의존성 캐시 레이어 ──────────────────────────────────────────────────────
# [원리] 도커 이미지는 명령(줄)마다 "레이어"로 쌓이고, 각 레이어는 캐시된다.
# 어떤 줄의 입력(COPY 대상 파일 내용 등)이 이전 빌드와 같으면, 도커는 그 줄을
# 다시 실행하지 않고 캐시된 레이어를 재사용한다. 단, 한 줄이라도 캐시가 깨지면
# 그 아래 줄은 전부 다시 실행된다(캐시 무효화가 아래로 전파됨).
#
# [전략] 그래서 "잘 안 바뀌는 것 → 자주 바뀌는 것" 순서로 COPY 한다.
# 만약 `COPY . .` 로 전부 한 번에 복사하면, 소스 한 줄만 고쳐도 이 레이어의
# 캐시가 깨져 의존성을 매번 새로 내려받게 된다(느림).

# 1) Gradle 래퍼 실행에 필요한 최소 파일만 복사한다.
COPY gradlew settings.gradle build.gradle ./

# 2) 래퍼 배포본(사용할 Gradle 버전·검증 정보)이 담긴 gradle/ 디렉터리 통째 복사.
COPY gradle ./gradle

# 3) 의존성만 미리 내려받아 별도 레이어로 굳힌다.
#    - --no-daemon      : 컨테이너는 한 번 쓰고 버리므로 데몬을 띄우지 않는다
#    - || true          : 이 단계가 실패해도 빌드를 멈추지 않는다("캐시 워밍업"일 뿐)
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 실행 가능 jar 빌드
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ── 2) runtime stage ───────────────────────────────────────────────────────
# JDK가 아닌 JRE만 담아 이미지를 가볍게. 비루트 사용자로 실행하고, 업로드 디렉터리는
# 볼륨 마운트 지점으로 준비한다. 헬스체크용 curl만 최소 설치한다.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --home-dir /app spring \
    && mkdir -p /app/uploads

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown -R spring:spring /app

USER spring

# 업로드 저장 루트를 컨테이너 내부 절대경로로 고정(볼륨이 여기에 마운트된다).
# 나머지 비밀값/DB 접속 정보는 compose의 env로 주입한다(이미지에 굽지 않는다).
ENV APP_UPLOAD_DIR=/app/uploads

EXPOSE 8090

# 컨테이너 PID 1이 자바 프로세스가 되도록 exec 형식 사용(SIGTERM 정상 전달 → graceful shutdown)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

## 6-2. `/Users/eunbumkim/Documents/practice/java/board/.dockerignore` (전문)

```
# 빌드 컨텍스트에서 제외 — 이미지 크기·빌드 속도·보안(비밀값 미포함)
.git
.gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar

# 비밀값은 이미지에 굽지 않는다 — 런타임에 compose env로 주입
.env

# 런타임/로컬 산출물
uploads/
*.log

# IDE / OS
.idea/
*.iml
.vscode/
.DS_Store

# 이 저장소 부속물(백엔드 이미지와 무관)
docs/
.claude/
.omc/
scripts/
```

## 6-3. `/Users/eunbumkim/Documents/practice/java/board/docker-compose.yml` (전문)

```yaml
# 테스트 환경: 별도 MySQL 컨테이너를 띄우지 않고, 기존에 쓰던 mysql-8 컨테이너와
# 전용 브리지 네트워크(board-db-net)로 묶어 백엔드만 실행한다.
#
#   docker compose up --build      # 빌드 후 기동
#   docker compose up -d           # 백그라운드
#   docker compose logs -f app     # 앱 로그
#   docker compose down            # 앱만 정지(mysql-8 · board-db-net 은 유지)
#
# 전제(최초 1회 세팅):
#   docker network create board-db-net
#   docker network connect board-db-net mysql-8
# 앱은 같은 네트워크에서 컨테이너명 "mysql-8" 로 DB에 접속한다(root/1234, DB: board).
#
# 비밀값(KAKAO_*/GOOGLE_*)은 .env 에서 주입된다. .env 가 없으면 카카오 프로퍼티
# fail-fast 검증에서 기동이 실패하므로, 먼저 `cp .env.example .env` 후 값을 채운다.
name: board

services:
  app:
    build:
      context: .
      dockerfile: Dockerfile
    image: ghcr.io/icesnake72/board-app:latest        # CI가 빌드·push, 서버는 pull만
    container_name: board-app
    depends_on:
      redis:
        condition: service_healthy   # 단계 15: 토큰 저장소가 준비된 뒤 앱 시작
    env_file:
      - .env                          # KAKAO_*/GOOGLE_* 주입 (기동 필수)
    environment:
      # 기존 mysql-8 컨테이너를 컨테이너명으로 접속(같은 board-db-net 안에서 DNS 해석).
      DB_HOST: mysql-8
      DB_PORT: "3306"
      DB_NAME: ${DB_NAME:-board}
      DB_USERNAME: ${DB_USERNAME:-root}
      DB_PASSWORD: ${DB_PASSWORD:-1234}
      APP_UPLOAD_DIR: /app/uploads
      REDIS_HOST: redis            # 단계 15: 같은 네트워크의 redis 서비스명
      TZ: Asia/Seoul
      # 운영(HTTPS) 전환 시 아래를 오버라이드한다.
      # JWT_SECRET: <운영 전용 Base64 시크릿으로 교체>
      # APP_REFRESH_COOKIE_SECURE: "true"
    # 백엔드는 호스트에 포트를 publish 하지 않는다 → 외부에서 직접 접근 불가.
    # 프론트(Nginx)만 같은 board-db-net 안에서 board-app:8090 으로 접근한다.
    volumes:
      - uploads:/app/uploads          # 업로드 이미지 영속화
    networks:
      - board-db-net
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8090/api/v1/boards"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 40s
    restart: unless-stopped

  # React(Vite) 메인 프론트 — 빌드는 멀티스테이지(Node→Nginx), Nginx가 정적 서빙 + /api 프록시.
  # 프론트 재편 처리에 의해 변경 — 디렉토리 frontend-react → frontend 개명, 서비스/이미지/
  # 컨테이너 이름도 frontend 계열을 승계. 순수 JS 원형은 frontend-vanilla/ 로 이동해
  # 학습 자료로만 보존한다(배포 대상 아님 — 서버의 옛 컨테이너는 --remove-orphans가 정리).
  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    image: ghcr.io/icesnake72/board-frontend:latest
    container_name: board-frontend
    depends_on:
      app:
        condition: service_healthy   # 백엔드가 응답 가능해진 뒤 노출
    # HTTPS 도입: 호스트 80 공개를 caddy에 넘기고 내부 전용화.
    # 외부 접근은 caddy(80/443) → 이 nginx → board-app 경로만 남는다.
    networks:
      - board-db-net                  # board-app 과 같은 네트워크(컨테이너명 프록시)
    healthcheck:
      # localhost는 컨테이너 안에서 ::1(IPv6)로 먼저 해석되는데 nginx는 IPv4만 리슨한다
      # → 127.0.0.1 로 명시해야 연결된다.
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1/"]
      interval: 10s
      timeout: 5s
      retries: 6
      start_period: 10s
    restart: unless-stopped

  # HTTPS 도입: 공개 진입점(TLS 종료 계층). 인증서 발급·갱신은 Caddy가 전자동으로
  # 처리하고, 우리가 관리하는 것은 caddy/Caddyfile 하나다(설계: HTTPS-DOMAIN.md).
  caddy:
    image: caddy:2-alpine
    container_name: board-caddy
    depends_on:
      frontend:
        condition: service_healthy   # 프록시 대상이 준비된 뒤 진입점 개방
    ports:
      - "80:80"                      # http — LE 챌린지 + (최종형에서) https 리다이렉트
      - "443:443"                    # https — 공개 진입점(신규)
    environment:
      # 로컬은 기본값(http://localhost), 서버는 deploy.sh의 .env가 실주소를 주입
      SITE_ADDRESS: ${SITE_ADDRESS:-http://localhost}
    volumes:
      # 디렉토리 마운트(파일 아님): git reset·에디터 저장은 파일을 "새 inode로 교체"하는데,
      # 단일 파일 bind mount는 옛 inode를 계속 붙들어 컨테이너 안 내용이 갱신되지 않는다
      # (실제 사고: 프론트 재편 배포에서 caddy가 삭제된 옛 업스트림을 찾다 전면 502).
      - ./caddy:/etc/caddy:ro
      - caddy-data:/data             # 인증서 보관 — 유실 시 재발급(LE 한도와 엮임), 프루닝 금지
      - caddy-config:/config
    networks:
      - board-db-net
    restart: unless-stopped

  # 단계 15: 토큰 저장소(refresh TTL + access denylist). 데이터 휘발 허용 설계라
  # compose가 수명주기를 관리한다(mysql-8과 달리 external 아님). host publish 없음(비공개).
  redis:
    image: redis:7-alpine
    container_name: board-redis
    # maxmemory 64mb: 2GB 인스턴스 예산 배려. noeviction: 토큰 임의 축출=강제 로그아웃이므로 금지
    command: ["redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction"]
    networks:
      - board-db-net
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 6
    restart: unless-stopped

networks:
  # 기존 mysql-8 이 물려 있는 전용 브리지 네트워크(외부 생성). compose가 지우지 않는다.
  board-db-net:
    external: true

volumes:
  uploads:   # 업로드 이미지
  caddy-data:   # Let's Encrypt 인증서·계정 키 (재시작·재배포에도 유지 — 중요)
  caddy-config:
```

**서비스 / 네트워크 / 포트 정리**

| 서비스 | 이미지 | 컨테이너명 | 호스트 포트 | 내부 포트 | 네트워크 |
|---|---|---|---|---|---|
| `app` | `ghcr.io/icesnake72/board-app:latest` | `board-app` | 없음 | 8090 | `board-db-net` |
| `frontend` | `ghcr.io/icesnake72/board-frontend:latest` | `board-frontend` | 없음 | 80 | `board-db-net` |
| `caddy` | `caddy:2-alpine` | `board-caddy` | 80, 443 | 80, 443 | `board-db-net` |
| `redis` | `redis:7-alpine` | `board-redis` | 없음 | 6379 | `board-db-net` |
| (compose 밖) | `mysql:8.0.33` | `mysql-8` | 없음 | 3306 | `board-db-net` |

네트워크 `board-db-net` 은 `external: true` 입니다. compose가 생성하거나 삭제하지 않습니다. 볼륨은 `uploads`, `caddy-data`, `caddy-config`, 그리고 compose 밖의 `mysql8-data` 입니다.

기동 의존 순서는 `redis(healthy) → app(healthy) → frontend(healthy) → caddy` 입니다.

**외부에 열린 포트는 caddy의 80/443 뿐입니다.** 백엔드도 Redis도 MySQL도 호스트 publish가 없습니다.

## 6-4. `/Users/eunbumkim/Documents/practice/java/board/caddy/Caddyfile` (전문)

```
# Caddy 설정 — 공개 진입점(TLS 종료 계층). 설계는 docs/lecture/HTTPS-DOMAIN.md.
#
# SITE_ADDRESS 환경변수 하나로 세 가지 모드를 오간다 (compose가 주입):
#   http://localhost        — 로컬 개발 기본값. TLS 없이 그대로 통과
#   http://sbs.alldayai.org — 서버 전환기. Cloudflare 프록시(주황 구름)가 켜져 있는
#                             동안 오리진에서 https 리다이렉트를 하면 CF Flexible 모드와
#                             무한 루프가 되므로, http 명시로 리다이렉트·발급을 끈다
#   sbs.alldayai.org        — 최종형. CF 프록시 해제(DNS only) 후 이 값으로 바꾸면
#                             Caddy가 Let's Encrypt 발급·갱신·https 리다이렉트까지 자동
{$SITE_ADDRESS:http://localhost} {
  reverse_proxy board-frontend:80 {
    # Caddy는 X-Forwarded-For/Proto와 Host를 자동 전달하지만 Port는 안 보낸다.
    # 백엔드의 forward-headers 계산(OAuth redirect_uri)에 필요해 명시한다.
    header_up X-Forwarded-Port {http.request.local.port}
  }
}

# IP 직접 접속 호환 — 기존 북마크·카카오 콘솔의 IP redirect URI가 계속 동작하도록
# http 그대로 서빙한다(IP에는 인증서 발급이 불가하므로 리다이렉트도 하지 않는다).
http://3.34.173.34 {
  reverse_proxy board-frontend:80 {
    header_up X-Forwarded-Port {http.request.local.port}
  }
}
```

배포 시 `SITE_ADDRESS=sbs.alldayai.org` 입니다. 스킴 없는 최종형이라 Caddy가 Let's Encrypt 발급과 90일 자동 갱신, http→https 리다이렉트까지 처리합니다.

## 6-5. 리버스 프록시 라우팅 규칙 (전체 경로)

```
브라우저 (https://sbs.alldayai.org)
  → board-caddy (80/443, TLS 종료, X-Forwarded-Port 추가)
    → board-frontend:80 (nginx)
      ├─ /                            → 정적 파일 (React dist), fallback /index.html
      ├─ /assets/                     → 정적 캐시 5분
      ├─ /api/                        → board-app:8090  (원본 URI 그대로)
      └─ ~ ^/(oauth2|login/oauth2)/   → board-app:8090  (+ X-Forwarded-Host/Port)
         → board-app:8090
           ├─ MySQL: mysql-8:3306
           └─ Redis: redis:6379
```

nginx의 두 가지 실전 대응이 채팅 서버에도 그대로 필요합니다.

```nginx
  # 단계 15 배포에서 발견된 함정의 해결: proxy_pass에 호스트명을 정적으로 쓰면 nginx가
  # 기동 시 1회만 IP를 해석해, 백엔드 컨테이너만 재생성되는 배포(GHCR pull) 후 502가 난다.
  # resolver(도커 내장 DNS 127.0.0.11) + 변수를 쓰면 요청마다 재해석해 새 IP를 따라간다.
  resolver 127.0.0.11 valid=10s ipv6=off;
  set $backend board-app:8090;
  ...
  proxy_pass http://$backend;
```

```nginx
# HTTPS 도입: 앞단 프록시(caddy)가 이미 X-Forwarded-Proto/Port를 달아 보냈다면
# 그 값을 승계하고, 직접 노출(로컬 등)일 때만 내 값($scheme 등)을 쓴다.
# 예전처럼 $scheme으로 덮어쓰면 내부 구간이 http라 "https로 왔다"는 사실이 지워져
# 백엔드의 OAuth redirect_uri가 http://로 계산되는 함정이 있다.
map $http_x_forwarded_proto $fwd_proto {
  ""      $scheme;
  default $http_x_forwarded_proto;
}
map $http_x_forwarded_port $fwd_port {
  ""      $server_port;
  default $http_x_forwarded_port;
}
```

**현재 nginx.conf에는 WebSocket 업그레이드 설정이 없습니다.** 채팅을 붙이려면 `proxy_set_header Upgrade $http_upgrade`, `proxy_set_header Connection "upgrade"`, 긴 `proxy_read_timeout` 을 새로 추가해야 합니다. caddy의 `reverse_proxy` 는 업그레이드를 자동 처리합니다.

## 6-6. `/Users/eunbumkim/Documents/practice/java/board/.github/workflows/deploy.yml` (전문)

```yaml
# ────────────────────────────────────────────────────────────────────────────
# GitHub Actions CI/CD — AWS Lightsail 배포 (무스왑 설계)
#
# 흐름: (1) test — 빌드·테스트(H2) 게이트
#       (2) build — 이미지 2종(app·frontend)을 러너(RAM 7GB)에서 빌드해 GHCR에 push
#       (3) deploy — SSH 접속 → 코드 최신화 → scripts/deploy.sh (pull + 실행만)
#
# 왜 서버에서 빌드하지 않나: 2GB 인스턴스에서 gradle+npm 빌드는 메모리를 넘겨
# 스왑/OOM 사고를 냈다. 빌드를 CI로 옮기면 서버 배포 부하는 "이미지 압축해제"
# 수준이라 스왑 없이 안전하다.
#
# 인증: 내장 GITHUB_TOKEN 하나로 GHCR push(러너)와 pull(서버 docker login)을
# 모두 처리한다 — 새 Secret 불필요. permissions.packages: write 가 그 근거.
#
# 필요한 GitHub Secrets(기존 그대로):
#   LIGHTSAIL_HOST, LIGHTSAIL_USER(ec2-user), LIGHTSAIL_SSH_KEY,
#   KAKAO_REST_API, KAKAO_SECRET, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET,
#   DB_NAME, DB_USERNAME, DB_PASSWORD
# ────────────────────────────────────────────────────────────────────────────
name: Deploy to Lightsail

on:
  push:
    branches: ["main"]
    paths-ignore:           # 문서만 바뀐 커밋은 재배포하지 않는다
      - "docs/**"
      - "**/*.md"
  workflow_dispatch:        # 수동 트리거(코드 변경 없이 재배포)

permissions:
  contents: read
  packages: write           # GHCR push/pull에 필요 — GITHUB_TOKEN에 부여됨

concurrency:
  group: deploy-lightsail
  cancel-in-progress: true

jobs:
  # ── 1) 빌드·테스트 게이트 — 테스트가 깨지면 배포하지 않는다(H2라 외부 의존 없음) ──
  test:
    runs-on: ubuntu-latest
    steps:
      - name: 소스 체크아웃
        uses: actions/checkout@v5

      - name: JDK 21 설치(Temurin)
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
          cache: gradle

      - name: 테스트 실행(H2)
        run: chmod +x ./gradlew && ./gradlew --no-daemon test

  # ── 2) 이미지 빌드·push — 러너에서 빌드하므로 서버는 빌드 부하 0 ──
  build:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - name: 소스 체크아웃
        uses: actions/checkout@v5

      - name: Buildx 설정(GHA 캐시 사용을 위해)
        uses: docker/setup-buildx-action@v3

      - name: GHCR 로그인
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      # cache-from/to type=gha: 러너가 매번 새 VM이어도 레이어 캐시가 워크플로 간 재사용됨
      - name: 백엔드 이미지 빌드·push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ghcr.io/icesnake72/board-app:latest
          cache-from: type=gha,scope=app
          cache-to: type=gha,mode=max,scope=app

      # 프론트 재편 처리에 의해 변경 — 순수 JS 프론트(frontend-vanilla)는 학습 자료로만
      # 남기고 빌드·배포 대상에서 제외. React가 frontend 이름을 승계한다.
      - name: 프론트(React) 이미지 빌드·push
        uses: docker/build-push-action@v6
        with:
          context: ./frontend
          push: true
          tags: ghcr.io/icesnake72/board-frontend:latest
          cache-from: type=gha,scope=fer
          cache-to: type=gha,mode=max,scope=fer

  # ── 3) 배포 — 서버는 pull + 실행만 (무스왑) ──
  deploy:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - name: Lightsail SSH 접속 후 배포
        uses: appleboy/ssh-action@v1.2.0
        # Secrets를 러너 env로 먼저 노출해야 envs:가 원격 셸로 전달할 수 있다.
        env:
          KAKAO_REST_API: ${{ secrets.KAKAO_REST_API }}
          KAKAO_SECRET: ${{ secrets.KAKAO_SECRET }}
          GOOGLE_CLIENT_ID: ${{ secrets.GOOGLE_CLIENT_ID }}
          GOOGLE_CLIENT_SECRET: ${{ secrets.GOOGLE_CLIENT_SECRET }}
          DB_NAME: ${{ secrets.DB_NAME }}
          DB_USERNAME: ${{ secrets.DB_USERNAME }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
          GHCR_USER: ${{ github.actor }}
          GHCR_TOKEN: ${{ secrets.GITHUB_TOKEN }}   # 서버가 GHCR pull할 때 쓰는 단명 토큰
        with:
          host: ${{ secrets.LIGHTSAIL_HOST }}
          username: ${{ secrets.LIGHTSAIL_USER }}
          key: ${{ secrets.LIGHTSAIL_SSH_KEY }}
          command_timeout: 10m       # 빌드가 없어졌으므로 10분이면 충분(pull+기동)
          envs: KAKAO_REST_API,KAKAO_SECRET,GOOGLE_CLIENT_ID,GOOGLE_CLIENT_SECRET,DB_NAME,DB_USERNAME,DB_PASSWORD,GHCR_USER,GHCR_TOKEN
          # 원격에서 할 일: (AL2023 부트스트랩) → 코드 최신화 → 배포 스크립트 실행
          script: |
            set -e
            command -v git >/dev/null || sudo dnf install -y git   # Amazon Linux 2023엔 git이 기본 미설치
            [ -d ~/board/.git ] || git clone https://github.com/icesnake72/boards.git ~/board
            cd ~/board
            git fetch --all && git reset --hard origin/main
            ./scripts/deploy.sh
```

## 6-7. scripts 디렉토리

| 파일 | 권한 | 역할 |
|---|---|---|
| `deploy.sh` | `-rwxr-xr-x` | 서버 배포 (CI가 SSH로 실행) |
| `verify.sh` | `-rwxr-xr-x` | 빌드 + 테스트 + 실제 기동 헬스체크 |
| `erd.sh` | `-rwxr-xr-x` | MySQL 스키마 → mermaid ERD 생성 (읽기 전용) |
| `ls_server_key.pem` | `-r--------` | SSH 개인키. **읽지 않았습니다.** |

**`scripts/deploy.sh` (전문)**

```bash
#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# 서버(Lightsail) 배포 스크립트 — GitHub Actions(deploy.yml)가 SSH로 실행한다.
#
# 무스왑 설계: 서버는 빌드하지 않는다. CI(GitHub Actions 러너, RAM 7GB)가
# 이미지를 빌드해 GHCR(ghcr.io)에 올리고, 이 스크립트는 pull + 실행만 한다.
#
# 순서: .env 생성 → DB(mysql-8) 준비 → GHCR 로그인 → pull → up --wait → 정리
#
# 전제: 저장소 루트에서 실행되고, 아래 환경변수가 주입되어 있다(워크플로 envs:):
#   KAKAO_REST_API, KAKAO_SECRET, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET,
#   DB_NAME, DB_USERNAME, DB_PASSWORD, GHCR_USER, GHCR_TOKEN
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail   # 오류·미정의변수·파이프 실패 시 즉시 중단

echo "▶ .env 생성(Secrets → 서버). 카카오 키는 fail-fast라 비면 기동 실패"
cat > .env <<EOF
KAKAO_REST_API=${KAKAO_REST_API}
KAKAO_SECRET=${KAKAO_SECRET}
GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}
DB_NAME=${DB_NAME}
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD=${DB_PASSWORD}
# HTTPS 진입점(caddy) 주소 — 비밀 아님. 스킴 없는 최종형: Caddy가 이 도메인으로
# Let's Encrypt 발급·90일 자동 갱신·http→https 리다이렉트까지 전자동 처리한다.
SITE_ADDRESS=sbs.alldayai.org
# HTTPS 최종 하드닝: refresh 쿠키를 https에서만 전송(도청 시 쿠키 탈취 차단).
# 로컬(http)은 compose 기본값 false 유지 — 서버만 켠다.
APP_REFRESH_COOKIE_SECURE=true
EOF

echo "▶ 전용 네트워크·mysql-8 준비(없으면 생성, 있으면 그대로)"
docker network create board-db-net 2>/dev/null || true
# start가 성공하면 이미 있는 컨테이너, 실패하면(=없음) run으로 새로 생성
docker start mysql-8 2>/dev/null || docker run -d --name mysql-8 \
  --network board-db-net \
  -e MYSQL_ROOT_PASSWORD="${DB_PASSWORD}" \
  -e MYSQL_DATABASE="${DB_NAME}" \
  -v mysql8-data:/var/lib/mysql \
  mysql:8.0.33 --default-time-zone=+09:00
docker network connect board-db-net mysql-8 2>/dev/null || true

echo "▶ DB 응답 대기(최대 60초) — 앱보다 DB가 먼저 준비되어야 한다"
timeout 60 bash -c \
  'until docker exec mysql-8 mysqladmin ping -uroot -p"$DB_PASSWORD" --silent 2>/dev/null; do sleep 2; done'
echo "  mysql-8 ready"

echo "▶ GHCR 로그인(워크플로 단명 토큰 — 패키지가 비공개여도 pull 가능)"
echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USER}" --password-stdin

echo "▶ 이미지 pull (서버 빌드 없음 — CI가 만든 이미지를 받기만 한다)"
docker compose pull

echo "▶ 재기동 + 헬스체크 통과까지 대기(--wait)"
# --no-build: 서버에서 실수로라도 빌드가 돌지 않게 명시(무스왑 설계의 안전핀)
# --wait: healthcheck 있는 서비스 전부 healthy까지 대기, 실패 시 exit≠0 → 배포 실패
# --remove-orphans: compose에서 삭제된 서비스의 잔존 컨테이너 정리
docker compose up -d --no-build --wait --remove-orphans

echo "▶ caddy 설정 반영(무중단 reload)"
# Caddyfile 내용 변경은 compose의 재생성 트리거가 아니다(마운트 경로·이미지가 같으면
# 컨테이너를 그대로 둔다). 그래서 매 배포마다 명시적으로 reload해 최신 설정을 적용한다.
docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile

docker logout ghcr.io

echo "▶ 옛 이미지 정리 + 최종 상태"
docker image prune -f
docker compose ps
```

**`scripts/verify.sh` 요약** (3단계 판정자, exit 0 = 통과)

- stage 1: `./gradlew build` (컴파일 + 전체 테스트, H2 기반)
- stage 2: 전제조건 확인. MySQL(127.0.0.1:3306) 응답, `.env` 존재, `KAKAO_SECRET`/`KAKAO_REST_API` 값 존재, 포트 8091 미점유. 미비 시 기동 검증을 SKIP 하고 통과 처리
- stage 3: `SERVER_PORT=8091 java -jar build/libs/*.jar` 기동 후 최대 45초 동안 `GET http://localhost:8091/api/v1/boards` 가 200이 되는지 폴링

**`scripts/erd.sh` 요약**: `docker exec mysql-8 mysql` 로 `information_schema.columns` 와 `key_column_usage` 를 SELECT 해 mermaid `erDiagram` 텍스트를 출력합니다. 읽기 전용입니다.

## 6-8. `.env` / `.env.example`

**`.env` (로컬)에 실제로 존재하는 키는 4개입니다. 값은 보고하지 않습니다.**

```
KAKAO_REST_API
KAKAO_SECRET
GOOGLE_CLIENT_ID
GOOGLE_CLIENT_SECRET
```

**`/Users/eunbumkim/Documents/practice/java/board/.env.example` (전문 — 키 이름과 예시값)**

```bash
# .env로 복사한 뒤 실제 값을 채운다: cp .env.example .env
# application.yaml의 spring.config.import가 이 파일을 로드한다 (OS 환경변수가 있으면 그쪽이 우선).
# docker compose도 이 파일을 읽어 컨테이너에 주입한다(env_file / ${VAR} 치환).

# ── OAuth 비밀값 (기동 필수) ────────────────────────────────────────────────
# 카카오 키는 로그인을 실제로 쓰지 않아도 반드시 non-empty 여야 한다.
# 비거나 미해석(${...})이면 KakaoOAuthProperties.@PostConstruct 검증에서 기동이 실패한다.
KAKAO_REST_API=your-kakao-rest-api-key
KAKAO_SECRET=your-kakao-client-secret

# 구글 (단계 8 확장) — console.cloud.google.com > API 및 서비스 > 사용자 인증 정보
# OAuth 클라이언트 ID(웹 애플리케이션) 생성, 승인된 리디렉션 URI에
# http://localhost:8090/login/oauth2/code/google 등록
GOOGLE_CLIENT_ID=your-google-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-google-client-secret

# ── DB 접속 (도커 미사용 시 생략하면 application.yaml 기본값 사용) ───────────
DB_NAME=board
DB_USERNAME=root
DB_PASSWORD=1234
DB_PORT=3306
# DB_HOST=localhost   # 로컬 실행용. compose에서는 자동으로 mysql 로 설정된다.

# ── 기타(선택) ──────────────────────────────────────────────────────────────
# JWT_SECRET=<Base64, HS256 최소 256비트>   # 운영에선 반드시 교체
# APP_REFRESH_COOKIE_SECURE=true            # 운영(HTTPS)에서 true
# APP_UPLOAD_DIR=./uploads                  # compose에서는 /app/uploads 로 설정된다

# DB_HOST
# DB_PORT
# DB_NAME
# DB_PASSWORD
# DB_USERNAME
# GOOGLE_CLIENT_ID
# GOOGLE_CLIENT_SECRET
# KAKAO_REST_API
# KAKAO_SECRET
# LIGHTSAIL_HOST
# LIGHTSAIL_SSH_KEY
# LIGHTSAIL_USER
# APP_UPLOAD_DIR
```

파일 하단 주석 블록이 전체 키 목록 역할을 합니다. `LIGHTSAIL_*` 3종은 GitHub Secrets 전용입니다.

## 6-9. 서버 / 도메인 정리

| 항목 | 값 |
|---|---|
| 호스팅 | AWS Lightsail, Amazon Linux 2023, RAM 2GB |
| 공개 IP | `3.34.173.34` |
| 도메인 | `sbs.alldayai.org` (Cloudflare DNS only 모드 필요) |
| 개방 포트 | 22, 80, 443 (8090은 열지 않음) |
| 배포 디렉토리 | `~/board` (git clone) |
| 이미지 레지스트리 | `ghcr.io/icesnake72/board-app`, `ghcr.io/icesnake72/board-frontend` |
| 저장소 | `https://github.com/icesnake72/boards.git` |
| 스왑 | 0B (무스왑 설계) |


---

# 7. 프론트엔드

디렉토리가 둘입니다. `frontend/` 가 production 메인(React)이고, `frontend-vanilla/` 는 학습 보존용(순수 JS, 배포 대상 아님)입니다.

## 7-1. frontend (React + Vite) — production 메인

**`/Users/eunbumkim/Documents/practice/java/board/frontend/package.json` (전문)**

```json
{
  "name": "board-frontend",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "^4.3.4",
    "vite": "^6.0.7"
  }
}
```

의존성이 React와 Vite뿐입니다. **라우터도, 상태 관리 라이브러리도, HTTP 클라이언트(axios)도 없습니다.** 화면 전환은 `App.jsx` 의 `useState` 로 처리하고 HTTP는 브라우저 `fetch` 를 직접 씁니다.

**구조**

```
frontend/
├── index.html            루트 <div id="root"> + /src/main.jsx
├── vite.config.js        dev 프록시 설정
├── package.json
├── Dockerfile            멀티스테이지 (node build → nginx)
├── nginx.conf            정적 서빙 + /api·/oauth2 프록시
└── src/
    ├── main.jsx          createRoot + StrictMode
    ├── App.jsx           화면 전환(boards → posts → post) + silent login
    ├── api.js            API 클라이언트 (토큰 관리의 전부가 여기 있다)
    ├── styles.css
    └── components/
        ├── AuthBar.jsx       로그인/회원가입/소셜 로그인/로그아웃
        ├── Boards.jsx        게시판 목록
        ├── Posts.jsx         글 목록(하이브리드 페이지네이션) + 작성
        └── PostDetail.jsx    글 상세 + 댓글 + 반응
```

**API base URL 설정 방식**: 설정 파일이나 환경변수가 **없습니다.** 모든 호출이 상대 경로 `/api/v1/...` 입니다. 개발 시는 Vite 프록시가, 배포 시는 nginx가 백엔드로 넘깁니다.

**`frontend/vite.config.js` (전문)**

```js
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 개발 서버에서 /api 를 백엔드로 프록시(로컬 npm run dev 용).
// 컨테이너 배포에서는 이 프록시가 아니라 Nginx(nginx.conf)가 프록시를 담당한다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:8090",
    },
  },
});
```

## 7-2. 인증 토큰 저장/전송 (`frontend/src/api.js` 인증부 전문)

**채팅 클라이언트 설계의 직접적 참고점입니다.**

```js
// 백엔드 API 클라이언트 — 인증 토큰 관리 + 401 자동 재발급(fetch 래퍼).
//
// 토큰 정책(단계 5와 짝):
//   - access token: 응답 본문으로 받아 "메모리"에만 보관(XSS로 털릴 localStorage 회피)
//   - refresh token: httpOnly 쿠키(JS가 못 읽음) — reissue/logout 때 브라우저가 자동 전송
// 메모리 보관이라 새로고침하면 access가 사라지므로, 앱 시작 시 silentLogin()으로
// reissue를 한 번 호출해 세션을 복원한다(쿠키가 살아 있으면 로그인 유지).

let accessToken = null;

export function isLoggedIn() {
  return accessToken != null;
}

// 에러 응답({code, message})을 Error로 변환 — 화면에서 err.message로 표시
async function toError(res) {
  let msg = `HTTP ${res.status}`;
  try {
    const body = await res.json();
    if (body.message) msg = `${body.message} (${body.code ?? res.status})`;
  } catch { /* 본문이 JSON이 아니면 상태코드만 */ }
  const err = new Error(msg);
  err.status = res.status;
  return err;
}

// refresh 쿠키로 access token 재발급. 성공 시 true.
async function reissue() {
  const res = await fetch("/api/v1/auth/reissue", {
    method: "POST",
    credentials: "include",          // httpOnly refresh 쿠키를 실어 보낸다
  });
  if (!res.ok) {
    accessToken = null;
    return false;
  }
  const data = await res.json();
  accessToken = data.accessToken;
  return true;
}

// 모든 API 호출의 공통 관문: Bearer 부착 + 401이면 reissue 후 1회 재시도.
export async function authFetch(url, options = {}) {
  const doFetch = () =>
    fetch(url, {
      ...options,
      credentials: "include",
      headers: {
        ...(options.headers ?? {}),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
    });
  let res = await doFetch();
  if (res.status === 401 && (await reissue())) {
    res = await doFetch();           // 새 access로 원요청 재시도
  }
  return res;
}

// JSON 요청/응답 헬퍼 — 실패 시 서버 메시지를 담은 Error를 던진다
async function jsonFetch(url, options = {}) {
  const res = await authFetch(url, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) },
  });
  if (!res.ok) throw await toError(res);
  return res.status === 204 ? null : res.json();
}

// ── 인증 ────────────────────────────────────────────────────────────────────
export async function signup({ username, email, password, nickname }) {
  return jsonFetch("/api/v1/auth/signup", {
    method: "POST",
    body: JSON.stringify({ username, email, password, nickname }),
  });
}

export async function login(username, password) {
  const data = await jsonFetch("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
  accessToken = data.accessToken;    // 본문의 access는 메모리에, refresh는 쿠키로 이미 저장됨
  return data;
}

export async function logout() {
  try {
    await authFetch("/api/v1/auth/logout", { method: "POST" });
  } finally {
    accessToken = null;              // 서버 실패와 무관하게 로컬 세션은 종료
  }
}

// 새로고침 후 세션 복원: refresh 쿠키가 살아 있으면 로그인 상태로 복귀
export async function silentLogin() {
  return reissue();
}

export async function getMe() {
  return jsonFetch("/api/v1/profiles/me");
}
```

**정리하면 다음과 같습니다.**

| 토큰 | 저장 위치 | 전송 방식 |
|---|---|---|
| access token | **모듈 스코프 변수 (JS 메모리)** | `Authorization: Bearer {token}` 헤더 |
| refresh token | **httpOnly 쿠키** (JS 접근 불가) | `credentials: "include"` 로 브라우저가 자동 전송 |

**localStorage 와 sessionStorage 는 전혀 사용하지 않습니다.** 전체 프론트 코드에서 0건입니다.

새로고침하면 access가 사라지므로 `App.jsx` 가 마운트 시 복원합니다.

```jsx
  // 앱 시작 시 refresh 쿠키로 세션 복원(access는 메모리라 새로고침에 사라지므로)
  useEffect(() => {
    (async () => {
      if (await silentLogin()) await refreshUser();
      setReady(true);
    })();
  }, [refreshUser]);
```

소셜 로그인은 fetch가 아니라 전체 리다이렉트로 시작합니다 (`AuthBar.jsx`).

```jsx
// 소셜 로그인 실패 시 백엔드가 /?error=OAUTH_LOGIN_FAILED 로 리다이렉트한다 — 한 번 읽고 URL 정리.
function consumeOauthError() {
  const err = new URLSearchParams(window.location.search).get("error");
  if (err) window.history.replaceState(null, "", window.location.pathname);
  return err ? `소셜 로그인에 실패했습니다 (${err})` : "";
}

// 소셜 로그인은 fetch가 아니라 "전체 리다이렉트"로 시작한다(제공자 동의 화면으로 이동).
// 성공하면 백엔드가 refresh 쿠키를 심고 "/"로 돌려보내고, SPA의 silent login이 세션을 복원한다.
function startSocialLogin(provider) {
  window.location.href = `/oauth2/authorization/${provider}`;
}
```

`api.js` 의 나머지 도메인 함수는 게시판, 게시글(offset/cursor/search), 댓글, 반응 호출이며 전부 `jsonFetch` 를 거칩니다. 파일 업로드만 `FormData` 로 `authFetch` 를 직접 씁니다.

```js
// 글 작성은 multipart: "post" 파트(JSON) + "images" 파트(파일들, 선택)
export async function createPost(boardId, { title, content }, files = []) {
  const form = new FormData();
  form.append("post", new Blob([JSON.stringify({ title, content })], { type: "application/json" }));
  for (const f of files) form.append("images", f);
  const res = await authFetch(`/api/v1/boards/${boardId}/posts`, {
    method: "POST",
    body: form,                      // Content-Type은 브라우저가 boundary와 함께 설정
  });
  if (!res.ok) throw await toError(res);
  return res.json();
}
```

## 7-3. frontend 빌드/배포

**`frontend/Dockerfile` (전문)**

```dockerfile
# React는 빌드 단계가 필요하다(JSX/모듈 → 정적 번들). 멀티스테이지로
# Node에서 빌드한 산출물(dist)만 경량 Nginx 이미지에 얹는다(순수 JS 버전은 빌드가 없었다).

# ── 1) build stage ─────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

# 의존성 캐시 레이어: 매니페스트만 먼저 복사 → 소스만 바뀐 재빌드에서 npm install 재사용
COPY package.json package-lock.json* ./
RUN npm install

# 소스 복사 후 정적 번들 생성(→ /app/dist)
COPY . .
RUN npm run build

# ── 2) runtime stage ───────────────────────────────────────────
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

**`frontend/nginx.conf` (전문)**

```nginx
# React 빌드 산출물(dist)을 서빙 + /api·/oauth2 프록시 — production 메인.
# HTTPS 도입 후 이 nginx는 caddy(TLS 종료) 뒤의 내부 계층이다.

# HTTPS 도입: 앞단 프록시(caddy)가 이미 X-Forwarded-Proto/Port를 달아 보냈다면
# 그 값을 승계하고, 직접 노출(로컬 등)일 때만 내 값($scheme 등)을 쓴다.
# 예전처럼 $scheme으로 덮어쓰면 내부 구간이 http라 "https로 왔다"는 사실이 지워져
# 백엔드의 OAuth redirect_uri가 http://로 계산되는 함정이 있다.
map $http_x_forwarded_proto $fwd_proto {
  ""      $scheme;
  default $http_x_forwarded_proto;
}
map $http_x_forwarded_port $fwd_port {
  ""      $server_port;
  default $http_x_forwarded_port;
}

server {
  listen 80;
  server_name _;

  # 단계 15 배포에서 발견된 함정의 해결: proxy_pass에 호스트명을 정적으로 쓰면 nginx가
  # 기동 시 1회만 IP를 해석해, 백엔드 컨테이너만 재생성되는 배포(GHCR pull) 후 502가 난다.
  resolver 127.0.0.11 valid=10s ipv6=off;
  set $backend board-app:8090;

  root /usr/share/nginx/html;
  index index.html;

  location / {
    # React 라우팅을 넣으면 이 폴백이 필수가 된다
    try_files $uri $uri/ /index.html;
  }

  # ── API 프록시 ──
  # location /api/: "/api/"로 시작하는 요청만 처리 → 백엔드로 넘긴다(브라우저 same-origin, CORS 불필요).
  location /api/ {
    proxy_pass http://$backend;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $fwd_proto;            # 원 스킴 — 앞단(caddy) 값 승계
  }

  # ── OAuth2 소셜 로그인 프록시 ──
  # 메인 진입점(80)이 된 이 프론트가 로그인 시작(/oauth2/authorization/*)과
  # 콜백(/login/oauth2/code/*)을 비공개 백엔드로 중계한다.
  location ~ ^/(oauth2|login/oauth2)/ {
    proxy_pass http://$backend;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $fwd_proto;   # redirect_uri의 스킴
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $fwd_port;     # 앞단 값 승계 — https면 443
  }

  # ── 정적 자산 캐시 (순수 JS 버전과 유일하게 다른 부분) ──
  # Vite는 번들을 /assets/ 아래에 해시 파일명으로 생성하므로 경로로 통째 잡는다.
  location /assets/ {
    expires 5m;
    add_header Cache-Control "public";
  }
}
```

빌드/배포 경로는 `GitHub Actions build 잡 → ghcr.io/icesnake72/board-frontend:latest → 서버 docker compose pull` 입니다.

## 7-4. frontend-vanilla (학습 보존용)

`package.json` 이 **없습니다.** 빌드 단계도 없습니다.

```
frontend-vanilla/
├── index.html      게시판 목록 화면 (스크립트는 app.js 한 개)
├── styles.css
├── app.js          fetch("/api/v1/boards") 만 호출
├── nginx.conf      React 버전과 거의 동일 (자산 캐시 블록만 다름)
└── Dockerfile      nginx:1.27-alpine 에 파일 3개 COPY (빌드 없음)
```

**`frontend-vanilla/Dockerfile` (전문)**

```dockerfile
# 순수 정적 프론트라 빌드 단계가 필요 없다 — 경량 nginx 이미지에 파일만 얹는다.
FROM nginx:1.27-alpine

# 기본 서버 설정을 우리 것으로 교체(정적 서빙 + /api 프록시)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 정적 파일 배치 (nginx 기본 문서 루트)
COPY index.html styles.css app.js /usr/share/nginx/html/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
```

**인증 코드가 전혀 없습니다.** `app.js` 는 다음 한 줄이 전부입니다.

```js
const API_BOARDS = "/api/v1/boards";
...
    const res = await fetch(API_BOARDS, { headers: { Accept: "application/json" } });
```

토큰도 로그인도 다루지 않습니다. XSS 방지를 위해 `innerHTML` 대신 `textContent` 만 씁니다.

nginx.conf는 React 버전과 동일한 구조이며 정적 자산 캐시 블록만 `location ~* \.(css|js)$` 로 다릅니다. 주석에 "이 학습용 프론트(8070)"라고 남아 있으나 **현재 compose 서비스 목록에 없어 배포되지 않습니다.** CI 빌드 대상에서도 제외되었고, 서버의 옛 컨테이너는 `docker compose up --remove-orphans` 가 정리합니다.


---

# 8. docs 디렉토리

구성은 `design/` 1개, `lecture/` 46개(.md 41 + .pptx 6 + .sql 1), 루트에 `무제.md`(0바이트 빈 파일)와 `refresh_and_access_flow.png` 입니다. `.obsidian/` 은 Obsidian 워크스페이스 설정이라 제외했습니다.

모든 lecture 문서는 Obsidian frontmatter(`step` / `track` / `tags` / `requires` / `status`)와 `[[위키링크]]` 로 엮인 커리큘럼 위키입니다.

## 8-1. 커리큘럼 구조 (`docs/lecture/000-MOC.md`)

Map of Content 허브입니다. 인증 트랙(단계 2~9) → 도메인 트랙(단계 10~13) → 인프라·성능 트랙(15~17)의 의존 지도를 mermaid로 그려 두었습니다.

| 단계 | 문서 | 주제 |
|---|---|---|
| 1 | INSTRUCTOR.md / STUDENT.md | 프로젝트 셋업, 3계층, HttpSession 인증 |
| 2 | JWT-AUTH.md | 세션 → stateless JWT 전환 |
| 3 | SPRING-SECURITY-STANDARD.md | 수동 JWT → Spring Security 표준 |
| 4 | REFRESH-TOKEN.md | access/refresh 분리, DB 저장 |
| 5 | HTTPONLY-COOKIE.md | refresh를 httpOnly 쿠키로 |
| 6 | METHOD-SECURITY.md | `@PreAuthorize` + 소유권 SpEL 빈 |
| 7 | OAUTH2-KAKAO.md | 카카오 OAuth2 수동 구현 |
| 8 | OAUTH2-CLIENT.md | oauth2-client 표준화 (strangler) |
| 9 | OIDC.md | 구글 OIDC 전환, nonce 보강 |
| 10 | FILE-UPLOAD.md | multipart 업로드 + 정적 서빙 |
| 11 | COMMENT.md | 1단계 대댓글, soft delete |
| 12 | NOTIFICATION.md | 이벤트 기반 인앱 알림 |
| 13 | REACTION.md | 좋아요/싫어요 3-상태 토글 |
| 15 | REDIS-TOKEN.md | Redis 토큰 저장소 + denylist |
| 16 | DB-PERFORMANCE.md | keyset 페이지네이션, 인덱스 |
| 17 | POST-SEARCH.md | FULLTEXT ngram 검색 |

단계 14는 결번이고 전 단계가 `status: 완료` 입니다. 태그 체계는 `#auth`, `#oauth2`, `#domain`, `#jpa`, `#event`, `#test`, `#docker`, `#cicd`, `#theory`, `#reference` 입니다.

새 단계 추가 규약(문서 작성 → frontmatter `requires` 기입 → 본문 위키링크 → MOC §1·§2 갱신 → 커밋)이 문서화되어 있습니다. 채팅 서버 문서를 같은 위키에 붙일 때 그대로 따르면 됩니다.

## 8-2. 인증 관련 핵심 문서 상세

**`docs/lecture/JWT-AUTH.md` (단계 2)**
세션에서 JWT로의 전환을 발급 경로와 검증 경로 두 갈래로 나눠 7단계로 구현합니다. 설정은 `jwt.secret`(Base64, HS256 최소 256bit)과 `access-token-validity-seconds: 3600` 입니다. `JwtTokenProvider` 가 암호 로직을 전부 캡슐화하고, 필터는 `OncePerRequestFilter` 를 상속해 요청을 막지 않고 통과시킵니다. 가장 중요한 후기 보강은 **필터 예외 3분기**입니다. `validateToken` 의 catch를 `JwtException | IllegalArgumentException` 으로 좁히고, `AuthenticationException` 은 컨텍스트만 비워 401로, 그 외 내부 오류는 `HandlerExceptionResolver` 에 위임해 500이 401로 둔갑하지 않게 만들었습니다.

**`docs/lecture/SPRING-SECURITY-STANDARD.md` (단계 3)**
`CustomUserDetails`, `CustomUserDetailsService`, `AuthenticationManager` 빈 노출이 3대 부품입니다. **토큰 subject가 userId에서 username으로 바뀌는 것**이 핵심 변경이며, 로그인과 매 요청 양쪽에서 `loadUserByUsername` 을 재사용하기 위함입니다. 인가 규칙에서 `/profiles/me` 를 반드시 `/profiles/*` 보다 먼저 선언해야 한다는 점, 403이 두 종류(역할 거부 vs 소유권 거부)라는 구분이 강조됩니다.

**`docs/lecture/SECURITY-FILTER-CHAIN.md` (보조 이론)**
요청이 `FilterChainProxy` → 내부 체인 → `DispatcherServlet` → `ArgumentResolver` → Controller로 흐르는 지도를 제시합니다. 우리 설정 기준 실제 체인은 `SecurityContextHolderFilter` → `HeaderWriterFilter` → `JwtAuthenticationFilter` → `AuthorizationFilter` 이며, 기동 로그의 "Will secure any request with [...]" 로 직접 확인하라고 안내합니다. 디버깅 팁으로 `logging.level.org.springframework.security.web.FilterChainProxy: DEBUG` 가 제시됩니다.

**`docs/lecture/REFRESH-TOKEN.md` (단계 4)**
access(JWT, 1시간, stateless)와 refresh(opaque UUID, 14일, DB 저장, stateful)로 역할을 분리합니다. refresh를 서버에 저장하는 이유는 검증, 만료 확인, 취소 세 가지이며 이때부터 로그아웃이 진짜 무효화가 됩니다. 회전(rotation)은 의도적으로 미적용입니다. 평문 UUID 저장은 강의 단순화이고 운영에서는 해시 저장을 권장한다는 단서가 있습니다. 단계 15에서 저장소가 Redis TTL로 이관되며 엔티티와 `EXPIRED_REFRESH_TOKEN` 분기가 소멸했다는 후기가 붙어 있습니다.

**`docs/lecture/HTTPONLY-COOKIE.md` (단계 5)**
`Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` 네 속성의 역할을 설명합니다. **비대칭 설계**가 결론입니다. access는 매 요청 `Authorization` 헤더에 실어야 하므로 응답 본문에서 JS 메모리로, refresh는 JS가 만질 일이 없으므로 httpOnly 쿠키에 숨깁니다. 로컬은 `APP_REFRESH_COOKIE_SECURE=false`, 운영은 반드시 true라는 함정도 기록되어 있습니다.

**`docs/lecture/REDIS-TOKEN.md` (단계 15 설계)**
키 스키마는 refresh가 양방향 2키 1쌍(`rt:{token}`, `rt:user:{userId}`, 둘 다 TTL 14일)이고 denylist는 `deny:{jti}` = `"1"` 에 TTL = exp - now 입니다. **만료 검사 로직이 소멸**하는 것이 백미로, TTL이 키를 지우므로 "없음 = INVALID_REFRESH_TOKEN" 하나로 단일화됩니다. denylisted access도 굳이 구분하지 않고 `LOGIN_REQUIRED` 401로 처리하며, **Redis 장애는 fail-closed(500)** 입니다. 인프라는 `redis:7-alpine` + `--maxmemory 64mb --maxmemory-policy noeviction`, AOF 미사용, 내부망, host publish 없음입니다. 실측으로 로그아웃 직후 같은 access가 즉시 401이 되고 `deny:{jti}` TTL이 3598초임을 확인했습니다.

**`docs/lecture/REDIS-TOKEN-WALKTHROUGH.md` (단계 15 따라하기)**
커밋 `7e592a5`(22파일, +410/−126)를 6개 작업으로 재현합니다. 원칙은 매 작업이 끝날 때마다 컴파일되고 테스트가 green입니다. 인터페이스 → Redis 구현 → InMemory 구현 → `TestTokenStoreConfig` → 그다음에야 `AuthService` 교체 순서로 진행합니다. 테스트 함정 두 개가 실전 지식입니다. `@Transactional` 롤백은 인메모리 Map을 되돌리지 못하므로 `@BeforeEach` 에서 `clear()` 를 호출해야 하고, 계약 테스트(`RefreshTokenStoreContractTest`)로 "사용자당 1개, 멱등, 사용자 격리"를 구현 무관하게 고정합니다.

**`docs/lecture/OAUTH2-KAKAO.md` (단계 7)**
라이브러리 없이 Authorization Code Grant 전 과정을 직접 코딩합니다. 카카오 access token은 즉시 버리고 우리 JWT 체계를 그대로 재사용한다는 개념 구분이 핵심입니다. `@ConfigurationProperties` 는 미해석 placeholder를 조용히 통과시키므로 `@PostConstruct` fail-fast가 필요하다는 함정, 그리고 **SameSite 차이**(refresh 쿠키는 Strict, state 쿠키는 반드시 Lax)가 중요합니다. 사용자 통합은 이메일이 아니라 `(provider, providerId)` 복합 UNIQUE로 합니다.

**`docs/lecture/OAUTH2-CLIENT.md` (단계 8)**
strangler 패턴으로 수동 경로 옆에 표준 경로를 병행해 매 단계 green을 유지합니다. 우리가 남기는 것은 4개 클래스뿐이며, 쿠키 저장소는 객체 통째 직렬화 대신 필요한 필드만 JSON으로 담아 CWE-502를 회피합니다. 구글 추가 비용을 실측해 yaml 3줄 + enum 1개 + switch 분기 1개 + provider 컬럼 ENUM→VARCHAR 전환이 전부였음을 보였습니다. 실전 함정 3개는 `@Configuration` 순환 의존(빈 메서드 파라미터로 해소), registration 0개면 컨텍스트 로드 실패, state 형식으로 어느 구현이 처리했는지 구분 가능입니다.

**`docs/lecture/OIDC.md` (단계 9)**
전환 비용은 딱 3곳입니다. yaml `openid` scope 1줄, `CustomOidcUserService` 신규 1클래스, `SecurityConfig` 의 `userInfoEndpoint` 이중 연결. 라이브러리가 `loadUser` 호출 전에 서명, iss, aud, exp, nonce를 모두 검증합니다. 가장 중요한 발견은 **nonce 검증 무음 스킵**입니다. 라이브러리가 `if (requestNonce == null) return;` 으로 조용히 건너뛰는데, 커스텀 쿠키 저장소가 nonce를 빠뜨려 검증이 사라져 있었고 필드를 추가해 해결했습니다.

**`docs/lecture/NOTIFICATION.md` (단계 12) — 채팅 서버와 직접 연결되는 문서**
`ApplicationEventPublisher` 로 `CommentCreatedEvent` 를 던지고, `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 로 수신합니다. AFTER_COMMIT은 원 트랜잭션이 이미 닫힌 시점이라 REQUIRES_NEW 없이 save하면 알림이 조용히 사라지는 것이 함정입니다. 대상 결정은 최상위 댓글이면 게시글 작성자, 대댓글이면 원댓글 작성자이며 recipient == actor면 스킵합니다. 소유 검증 실패는 403이 아니라 404로 은폐합니다(열거 방어). **문서가 명시적으로 "브라우저 실시간 푸시(WebSocket/SSE, STOMP 엔드포인트, 세션 매핑)는 이 단계 범위 밖"이라고 선을 긋고 폴링을 기본 UX로 둡니다.** 채팅 서버가 이어받을 정확한 지점입니다.

**`docs/lecture/EXCEPTION-HANDLING.md` (보조 이론)**
"서비스는 던지기만 하고 `@RestControllerAdvice` 한곳이 응답으로 바꾼다"는 구조입니다. 하위 예외 클래스는 가독성 라벨일 뿐 HTTP 상태는 전적으로 `ErrorCode` 가 정합니다. 새 예외 추가 비용이 "ErrorCode 한 줄 + 서비스에서 throw" 뿐이라는 것이 결론입니다.

## 8-3. 인프라 관련 문서 상세

**`docs/lecture/HTTPS-DOMAIN.md`**
IP로는 Let's Encrypt 인증서를 받을 수 없어 도메인이 먼저 필요합니다. Caddy를 택한 이유는 기존 nginx를 한 줄도 고치지 않고 맨 앞에 TLS 종료 계층 한 층만 세우는 구조이기 때문입니다. 실제 적용에서 계획에 없던 변수 두 개를 만났습니다. Cloudflare 프록시 + Flexible 모드의 무한 리다이렉트 루프를 `{$SITE_ADDRESS}` 3모드 전환으로 해소했고, nginx가 `X-Forwarded-Proto $scheme` 으로 caddy 값을 덮어써 OAuth redirect_uri가 http로 계산되는 문제를 `map` 승계 방식으로 고쳤습니다. **프록시가 2단 이상이면 반드시 만나는 함정**입니다. 이후 단일 파일 bind mount의 inode 함정으로 전면 502가 난 사고와 그 해결(디렉토리 마운트 + 배포 시 `caddy reload`)도 기록되어 있습니다. Lightsail 443 개방 누락이 1순위 함정으로 명시되어 있습니다.

**`docs/lecture/DEPLOY-LIGHTSAIL.md`**
인스턴스(`3.34.173.34`, Amazon Linux 2023)를 한 번만 수동 준비하는 가이드입니다. 수동 작업은 사실상 Docker 설치 하나뿐이며, `usermod -aG docker ec2-user` 가 빠지면 파이프라인이 docker.sock permission denied로 실패합니다. 빌드를 CI로 옮겨 2GB 인스턴스에서 **Swap 0B 무스왑 운영**이 가능해졌습니다. 트러블슈팅 표에 "인증 API가 500 → board-redis 다운(fail-closed 설계라 우회하지 않음)" 이 명시되어 있습니다.

**`docs/lecture/CICD-GITHUB-ACTIONS.md`**
3잡 `needs:` 체인 구성과 GHCR 인증을 내장 `GITHUB_TOKEN` 하나로 처리하는 방식을 설명합니다. `--no-build` 가 서버 빌드 금지의 안전핀이고 `--wait` 가 별도 검증 루프 없이 배포 성공/실패를 판정합니다. `DB_HOST`, `DB_PORT`, `APP_UPLOAD_DIR` 는 compose가 강제 주입하므로 Secret이 필요 없습니다.

**`docs/lecture/DOCKER.md`**
멀티스테이지, 의존성 캐시 레이어, `-x test`, 비루트 실행, exec 형식 ENTRYPOINT를 설계 포인트로 정리합니다. `application.yaml` 이 이미 placeholder를 쓰고 있어 **애플리케이션 코드 변경 없이** 도커화된다는 것이 핵심 근거입니다. 실행 모드 A(compose가 MySQL까지 번들)와 B(기존 mysql-8에 external 브리지로 연결, 현재 채택)를 비교합니다. 운영 전환 체크리스트로 JWT_SECRET 교체, `APP_REFRESH_COOKIE_SECURE=true`, `ddl-auto: validate` + Flyway, 최소 권한 DB 계정, Actuator 헬스체크가 제시됩니다.

**`docs/lecture/FRONTEND-DEPLOY.md`**
리버스 프록시로 CORS를 원천 회피하는 구조를 다룹니다. nginx 3대 포인트는 `proxy_pass` 에 경로를 붙이지 않기, 컨테이너명 DNS 해석, `resolver` + 변수로 요청마다 재해석하기입니다. 컨테이너 헬스체크의 `localhost` 가 IPv6로 먼저 해석돼 실패하는 함정도 기록되어 있습니다. React 변형은 배포와 API 연동이 완전히 동일하고 빌드 단계만 추가됩니다.

## 8-4. 나머지 문서 목록

| 파일 | 요약 |
|---|---|
| `lecture/INSTRUCTOR.md` (75KB) | 강사용 커리큘럼. 90분 × 8세션(총 12시간) 진행 시나리오 |
| `lecture/STUDENT.md` (55KB) | 학생용 Step 1~7 실습 안내와 학습 순서 설명 |
| `lecture/HTTP-SESSION.md` | HTTP 무상태성과 쿠키/세션 원리, JSESSIONID curl 실습 |
| `lecture/METHOD-SECURITY.md` (단계 6) | `@PreAuthorize` + `@postSecurity` 커스텀 SpEL 빈. `-parameters` 필요 이유 |
| `lecture/CURL-TEST.md` | 전 구간 curl 명령과 기대 응답. 에러 응답/상태코드 요약표 |
| `lecture/TESTING-GUIDE.md` | 테스트 실행 방법과 리포트 확인법 |
| `lecture/UNIT-TESTING.md` (60KB) | JUnit 5 → AssertJ → Mockito → `@SpringBootTest` → MockMvc |
| `lecture/FILE-UPLOAD.md` (58KB) | 파일이 트랜잭션에 참여하지 않는 문제, `afterCommit` 삭제 패턴 |
| `lecture/COMMENT.md` (51KB) | 자기참조 대댓글, soft delete, `@BatchSize` N+1 회피 |
| `lecture/REACTION.md` (61KB) | 3-상태 토글, 별도 테이블 집계 vs 비정규화 트레이드오프 |
| `lecture/REDIS-BASICS.md` | 비전공자용 Redis 입문. `rt:*`/`deny:*` 키 관찰 실습 |
| `lecture/DB-PERFORMANCE.md` | DB 성능 개선 커리큘럼 로드맵 |
| `lecture/DB-PERFORMANCE-LAB.md` | 게시글 100만 건 실습, 인덱스와 keyset |
| `lecture/DB-PERFORMANCE-WALKTHROUGH.md` (40KB) | 복합 인덱스 + keyset 쿼리 + React 무한스크롤 구현 기록 |
| `lecture/POST-SEARCH.md` | FULLTEXT + ngram 파서 설계, Elasticsearch 전환 기준 |
| `lecture/POST-SEARCH-LAB.md` | LIKE 느림 재현 → FULLTEXT 재측정 |
| `lecture/POST-SEARCH-WALKTHROUGH.md` | DDL이 코드보다 먼저인 유일한 단계, 검색어 정제 |
| `lecture/FRONTEND-PAGINATION.md` | 프론트 React 단일화 재편, caddy inode 함정 사고 기록 |
| `lecture/GITHUB-ACTIONS-BASICS.md` | Actions 개념/용어/계층 구조 입문 참조 |
| `lecture/OAUTH2-KAKAO-WORKPLAN.md` | 단계 7 작업 계획과 설계 결정 근거 |
| `lecture/OAUTH2-CLIENT-WORKPLAN.md` | 단계 8 strangler 전환 전략과 리스크 |
| `design/2026-06-12-board-design.md` | 단계 1 설계 문서. 4테이블 스키마, 단방향 연관만 사용, 당시 인증은 HttpSession |
| `무제.md` | **0바이트 빈 파일** |

**비-md 파일**

| 파일 | 용도 |
|---|---|
| `lecture/SPRING-SECURITY-OVERVIEW.pptx` | Spring Security 개요 슬라이드 (56KB) |
| `lecture/SESSION-VS-JWT.pptx` | 세션 vs JWT 비교 슬라이드 (59KB) |
| `lecture/HTTP-SESSION.pptx` | HTTP/HttpSession 이론 슬라이드 (99KB) |
| `lecture/HTTPONLY-COOKIE.pptx` | httpOnly 쿠키 슬라이드 (41KB) |
| `lecture/REFRESH-TOKEN.pptx` | Refresh Token 슬라이드 (40KB) |
| `lecture/METHOD-SECURITY.pptx` | 메서드 보안 슬라이드 (43KB) |
| `lecture/db_performance_lab.sql` | 재귀 CTE로 posts 더미 100만 건 생성 스크립트 |
| `refresh_and_access_flow.png` | access/refresh 발급·재발급 흐름 다이어그램 (94KB) |

---

# 9. .claude 디렉토리 (프로젝트 규칙)

**프로젝트 루트에 `CLAUDE.md` 는 없습니다.** `.claude/` 아래에 파일 3개만 있습니다.

| 파일 | 내용 |
|---|---|
| `.claude/settings.local.json` | `permissions.allow` 목록만. gradle, mysql, git, gh, curl, python venv 등 약 90개 항목 |
| `.claude/skills/verify-loop/SKILL.md` | 검증 루프 스킬 |
| `.claude/skills/wiki-sync/SKILL.md` | 위키 동기화 스킬 |

## 9-1. verify-loop 스킬 요약

결과 보증이 필요한 코드 수정(보안 취약점 점검·수정, 인증/인가 로직 변경, "완벽히 대비되게 고쳐줘" 수준)에 대해 「수정 → `./scripts/verify.sh` 실행 → 실패 시 원인 분석 후 재수정」을 통과할 때까지 반복합니다.

- 판정자는 `./scripts/verify.sh` 입니다. exit 0 = 통과, exit 1 = 실패
- **최대 5회 반복**. 초과하면 중단하고 시도 내역·현재 상태·막힌 지점을 보고합니다
- 같은 원인으로 2회 연속 실패하면 접근 방식 자체를 바꿉니다
- 오타·주석·문서 수정, 이름 바꾸기 수준의 리팩토링에는 **사용 금지**입니다. verify 1회에 1~2분이 걸려 낭비이기 때문입니다
- stage 2가 SKIP된 경우 보고에 그 사실을 명시해야 합니다

## 9-2. wiki-sync 스킬 요약

작업 결과를 `docs/lecture/` 강의 위키에 반영하는 on-demand 절차입니다. 사용자가 명시적으로 요청할 때만 실행합니다.

**핵심 원칙**: 코드가 정본(source of truth)입니다. 문서가 코드와 어긋나면 문서를 고칩니다. 인용 스니펫은 반드시 `Read` 로 실제 파일을 확인한 뒤 현재 시그니처와 일치시킵니다.

**문서 스타일 규칙 (채팅 프로젝트 문서화에도 그대로 적용 가능)**

- **ASCII 박스 금지** (`┌ ┐ └ ┘ │ ─` 등). 구조는 표나 mermaid로 표현합니다
- mermaid: 노드 라벨은 `["..."]`, `sequenceDiagram` 메시지에 콜론 금지, participant alias에 콤마 금지
- 코드/명령 블록 들여쓰기는 **스페이스 2칸**, 탭 금지
- 섹션 번호 + `---` 구분자 등 기존 문서 포맷을 따릅니다
- 교육 순서상 의도된 차이는 보존합니다

**frontmatter 형식**

```yaml
---
step: <번호>            # 단계에 매이지 않는 이론/참조 문서는 생략 가능
track: auth | domain    # 트랙 분류
tags: [<주제 태그들>]
requires: ["[[선수문서]]"]   # 본문 [[위키링크]]와 함께 그래프의 원천
status: 완료 | 진행중
---
```

**커밋 규칙**: 문서만 스테이징합니다(`git add docs/...`). 코드 파일은 이 스킬에서 건드리지 않습니다. Obsidian 워크스페이스 파일은 커밋하지 않습니다. 커밋 메시지는 `docs:` 접두어 + 무엇을 왜 바꿨는지 + 코드 무변경 명시입니다.

**기록된 사용자 선호**: "매 턴 도는 Stop hook은 낭비"라고 명시적으로 거부한 이력이 스킬 문서에 남아 있습니다. 자동 실행을 하지 않습니다.

---

# 10. 최근 git log 20개

```
396be5b docs: 단계 17 따라하기 문서 + 위키 완결 (코드 무변경)
f0c0183 feat: 단계 17 — 게시글 검색 (FULLTEXT ngram + keyset)
f2f1c39 docs: WALKTHROUGH 완전 자기완결화 — 전 코드 수록 + 진행 체크리스트
25d5e4a docs: wiki-sync — 프론트 재편·페이지네이션·caddy 사고 위키 반영 (코드 무변경)
0c36871 fix: caddy 설정 반영 누락 재발 방지 — 디렉토리 마운트 + 배포 시 reload
e3eb2f9 feat: offset 목록에 지연 조인 적용 — deep page 점프 3.2s → 60ms
f4f01aa docs: LAB §6-1 — 조인 낀 deep offset의 참혹함과 지연 조인 처방 (실측)
b4b7c54 feat: 프론트 재편 — React가 frontend 승계 + 하이브리드 페이지네이션 UI
291aeb0 fix: Auditing 시각을 마이크로초로 절단 — keyset 커서 중복 버그 수정
8cdea8f docs: 단계 17 게시글 검색 설계+실습 문서 (전 구간 실측, 코드 무변경)
185dd20 docs: 단계 16 실습 SQL 노트 추가 (실측 EXPLAIN ANALYZE 주석 포함)
dd12fb1 docs: 단계 16 따라하기 문서 + 위키 상태 갱신 (코드 무변경)
1dc2ab8 feat: 단계 16 — keyset(cursor) 페이지네이션 + React 무한스크롤
bcfe284 docs: 단계 16 실습 문서 + HTTPS 구글 로그인 E2E 최종 확인 기록
0429b29 docs: HTTPS 적용 완료 기록 — HTTPS-DOMAIN §10 + 배포 문서 역반영
4f67c6f feat: HTTPS 하드닝 — 서버 refresh 쿠키 Secure 전환
a8c4cfe feat: HTTPS 최종 전환 — SITE_ADDRESS를 LE 자동 발급 모드로
620c911 feat: HTTPS 진입점(caddy) 도입 — TLS 종료 계층 + 프록시 헤더 승계 체인
f34e146 docs: CURL-TEST §6에 denylist 전/후 로그아웃 검증 시나리오 추가
eaf6e47 docs: REDIS-BASICS 실습 curl 경로 수정 + RedisRefreshTokenStore 학습 주석
```

| 항목 | 값 |
|---|---|
| remote | `https://github.com/icesnake72/boards.git` |
| 현재 브랜치 | `main` |

커밋 메시지는 `feat:` / `fix:` / `docs:` 접두어와 한국어 본문, 그리고 대시로 근거를 덧붙이는 형식이 일관됩니다. 문서 전용 커밋에는 "코드 무변경"을 명시합니다.

`.gitattributes`

```
/gradlew text eol=lf
*.bat text eol=crlf
*.jar binary
```


---

# 11. 부록 — 채팅 서버 설계 시 반드시 알아야 할 사항

## 11-1. 그대로 재사용 가능한 것

1. **`JwtTokenProvider` 는 외부 의존이 없습니다.** `jwt.secret` 과 `jwt.access-token-validity-seconds` 두 프로퍼티만 있으면 채팅 서버에 그대로 복사해 동작합니다. Spring Security도 DB도 필요 없습니다.
2. **토큰 검증에 board 서버 호출이 필요 없습니다.** 같은 `JWT_SECRET` 을 공유하면 채팅 서버가 독립적으로 HS256 서명을 검증합니다.
3. **denylist 확인도 Redis 직접 조회로 가능합니다.** `deny:{jti}` 키 하나만 보면 됩니다. `board-db-net` 에 붙으면 `redis:6379` 로 접근합니다.
4. **`ErrorCode` / `ErrorResponse` / `BusinessException` 계층**과 **`RefreshCookieFactory` 패턴**을 그대로 옮기면 응답 형식이 일관됩니다.
5. **프론트의 토큰 관리 패턴**(access는 메모리, refresh는 httpOnly 쿠키, 401 시 reissue 후 1회 재시도)을 채팅 클라이언트에도 그대로 적용할 수 있습니다.
6. **인프라 관례**: `resolver` + 변수 `proxy_pass`, `X-Forwarded-Proto` map 승계, `forward-headers-strategy: framework`, compose `board-db-net` external 네트워크, GHCR 이미지 + 무스왑 배포.

## 11-2. 새로 해결해야 할 문제

1. **JWT에 userId가 없습니다.** `sub` 가 username입니다. 채팅에서 `userId` 가 필요하면 DB 조회가 추가로 발생합니다. 선택지는 세 가지입니다.
   - board DB(`users`, `user_profiles`)를 채팅 서버도 읽는다 (읽기 전용 공유)
   - 채팅 서버에 username → (userId, nickname) 캐시를 둔다 (Redis)
   - 토큰에 claim을 추가한다 → board의 `createToken` 수정이 필요하므로 **board 무수정 원칙과 충돌**합니다
2. **refresh 쿠키 path가 `/api/v1/auth` 입니다.** WebSocket 핸드셰이크 경로에 자동으로 실리지 않습니다. 채팅 클라이언트는 access token을 **STOMP CONNECT 프레임 헤더**에 직접 담아야 합니다. 쿼리 파라미터는 접근 로그에 남으므로 피하는 편이 좋습니다.
3. **CORS 설정이 전혀 없습니다.** 채팅 서버를 별도 origin으로 노출하면 `CorsConfigurationSource` 와 `setAllowedOrigins` 를 새로 작성해야 합니다. board 방식(nginx same-origin 프록시)을 따르면 CORS 없이 갈 수 있습니다. SockJS를 쓰면 별도의 `setAllowedOriginPatterns` 도 필요합니다.
4. **nginx.conf에 WebSocket 업그레이드 설정이 없습니다.** 다음을 새로 추가해야 합니다.
   ```nginx
   location /ws/ {
     proxy_pass http://$chat_backend;
     proxy_http_version 1.1;
     proxy_set_header Upgrade $http_upgrade;
     proxy_set_header Connection "upgrade";
     proxy_set_header Host $host;
     proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
     proxy_set_header X-Forwarded-Proto $fwd_proto;
     proxy_read_timeout 3600s;
     proxy_send_timeout 3600s;
   }
   ```
   caddy의 `reverse_proxy` 는 업그레이드를 자동 처리하므로 수정이 필요 없습니다.
5. **장수명 연결의 토큰 만료 처리가 미해결 영역입니다.** access token은 1시간입니다. CONNECT 시점 1회 검사만으로는 로그아웃 즉시 폐기와 만료가 보장되지 않습니다. 대안은 주기적 재검증(heartbeat마다 denylist 확인), Redis pub/sub으로 폐기 이벤트 구독, 또는 SEND 프레임마다 검사입니다.
6. **`@RestControllerAdvice` 는 STOMP 경로를 커버하지 않습니다.** `@MessageExceptionHandler` 와 `StompSubProtocolErrorHandler` 를 별도로 두어야 합니다. board의 필터 예외 3분기 설계(인증 실패는 통과시켜 401, 내부 오류는 위임해 500)와 같은 원칙을 적용하면 일관됩니다.
7. **Redis 정책 충돌 가능성**이 있습니다. 현재 `board-redis` 는 `maxmemory 64mb` + `noeviction` + AOF 없음입니다. 채팅 메시지 백플레인이나 세션 저장에 같은 인스턴스를 쓰면 메모리가 부족해지고, noeviction이라 쓰기가 거부되면 **토큰 저장까지 실패해 로그인이 깨집니다.** 별도 DB 인덱스 분리나 별도 인스턴스를 권합니다.
8. **의존성 추가가 필요합니다.** `spring-boot-starter-websocket` 이 없습니다. 외부 브로커(RabbitMQ STOMP relay)를 쓴다면 `spring-boot-starter-reactor-netty` 도 필요합니다.
9. **Redis 장애 시 fail-closed 정책**이 board의 원칙입니다. 채팅 서버도 같은 원칙을 따를지, 아니면 이미 연결된 세션은 유지할지 결정해야 합니다.

## 11-3. STOMP 인증 구현 스케치 (board 패턴 이식)

`JwtAuthenticationFilter` 의 로직을 `ChannelInterceptor` 로 옮기는 형태가 자연스럽습니다.

```java
// 개념 스케치 — board의 JwtAuthenticationFilter 로직을 CONNECT 프레임에 적용
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

  private final JwtTokenProvider tokenProvider;       // board에서 그대로 복사
  private final TokenDenylist tokenDenylist;          // board에서 그대로 복사
  private final CustomUserDetailsService userDetailsService;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
      String bearer = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
      // "Bearer " 제거 → validateToken → isDenied(getJti) → loadUserByUsername
      // → accessor.setUser(new UsernamePasswordAuthenticationToken(...))
      // 실패 시 예외를 던져 CONNECT를 거부한다 (HTTP 필터와 달리 여기서는 막아야 한다)
    }
    return message;
  }
}
```

board의 HTTP 필터는 "막지 않는다"가 원칙이지만, STOMP CONNECT는 **막아야 합니다.** 공개 엔드포인트 개념이 없기 때문입니다. 이 차이가 이식 시 가장 중요한 판단 지점입니다.

`accessor.setUser()` 로 설정한 `Principal.getName()` 은 board와 동일하게 **username** 이 됩니다. `convertAndSendToUser(username, ...)` 로 1:1 전송이 가능합니다.

## 11-4. 조사 범위와 제약

- 파일 수정은 없었습니다. 모든 작업이 읽기 전용이었습니다.
- `webserver_key.pem` 과 `scripts/ls_server_key.pem` 은 열지 않았습니다.
- `.env` 는 키 이름만 확인했고 값은 읽지 않았습니다.
- `jwt.secret` 과 `DB_PASSWORD` 의 yaml 기본값은 이 보고서에서 마스킹했습니다.
- `.git`, `build`, `.gradle`, `.playwright-mcp`, `node_modules`, `uploads` 디렉토리는 조사에서 제외했습니다.
- `docs/` 세부 요약은 Explore 서브에이전트가 읽기 전용으로 수행한 결과입니다.
