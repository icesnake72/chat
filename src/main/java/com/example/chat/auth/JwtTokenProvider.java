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
