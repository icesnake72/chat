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
