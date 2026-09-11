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
