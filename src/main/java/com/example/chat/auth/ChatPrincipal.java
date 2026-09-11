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
