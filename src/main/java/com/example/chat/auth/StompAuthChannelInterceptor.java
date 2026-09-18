package com.example.chat.auth;

import com.example.chat.global.exception.ErrorCode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

// STOMP = Simple Text Oriented Messaging Protocol. WebSocket 위에 얹어서 쓰는 메시징 규약입니다.
// 목적: WebSocket 자체는 "양방향으로 바이트를 주고받는 통로"만 제공합니다.
// 그 안에서 오가는 데이터가 무슨 의미인지에 대한 규칙이 전혀 없습니다.

// HTTP 필터와 달리 STOMP에는 공개 엔드포인트가 없으므로 CONNECT를 "막는다" — board 패턴 이식의 유일한 의도적 차이.
// 이후 SEND/SUBSCRIBE마다 만료·denylist를 재검사해 로그아웃·만료가 장수명 연결에도 반영되게 한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

  private final BearerTokenAuthenticator authenticator;
  private final JwtTokenProvider tokenProvider;
  private final TokenDenylist tokenDenylist;
  private final SubscriptionAuthorizer subscriptionAuthorizer;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    // Stomp header 접근자 생성 -> 들어오는 message로부터 접근자를 얻을 수 있다.
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    switch (accessor.getCommand()) {
      case CONNECT -> authenticateConnect(accessor);
      case SEND -> requireLivePrincipal(accessor);
      case SUBSCRIBE -> authorizeSubscribe(accessor);
      default -> {
      }
    }
    return message;
  }

  // 인증(살아 있는 토큰) 다음에 인가(이 destination을 구독해도 되는가)
  private void authorizeSubscribe(StompHeaderAccessor accessor) {
    ChatPrincipal principal = requireLivePrincipal(accessor);
    if (!subscriptionAuthorizer.canSubscribe(accessor.getDestination(), principal)) {
      throw new StompAuthException(ErrorCode.NOT_ROOM_MEMBER);
    }
  }

  // connect 시도시 stomp header로부터 토큰 검증 후 사용자 정보 삽입
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


  private ChatPrincipal requireLivePrincipal(StompHeaderAccessor accessor) {
    // connect시 삽입된 사용자 정보 추출
    ChatPrincipal principal = ChatPrincipal.from(accessor.getUser())
        .orElseThrow(() -> new StompAuthException(ErrorCode.LOGIN_REQUIRED));
    // 만료 여부 검사
    if (principal.isExpired(Instant.now())) {
      throw new StompAuthException(ErrorCode.TOKEN_EXPIRED);
    }
    // 로그아웃 여부 검사
    if (tokenDenylist.isDenied(principal.jti())) {
      throw new StompAuthException(ErrorCode.LOGIN_REQUIRED);
    }
    return principal;
  }
}
