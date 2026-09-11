package com.example.chat.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// board의 RedisTokenDenylist와 같은 키 스키마(deny:{jti}). Redis 장애는 예외를 삼키지 않는다 — fail-closed.
@Component
@RequiredArgsConstructor
public class RedisTokenDenylist implements TokenDenylist {

  private static final String KEY_PREFIX = "deny:";

  private final StringRedisTemplate redis;

  @Override
  public boolean isDenied(String jti) {
    return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
  }
}
