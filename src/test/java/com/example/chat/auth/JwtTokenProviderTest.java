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
