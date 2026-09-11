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
