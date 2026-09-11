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
