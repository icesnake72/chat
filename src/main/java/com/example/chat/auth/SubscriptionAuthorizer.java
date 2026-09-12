package com.example.chat.auth;

// SUBSCRIBE destination을 이 사용자가 구독해도 되는가. 구현은 room 패키지(auth → room 의존을 피한다).
public interface SubscriptionAuthorizer {

  boolean canSubscribe(String destination, ChatPrincipal principal);
}
