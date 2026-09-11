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
