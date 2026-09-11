package com.example.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisTokenDenylistTest {

  @Mock StringRedisTemplate redis;

  @Test
  void deniedWhenKeyExists() {
    given(redis.hasKey("deny:jti-1")).willReturn(true);

    assertThat(new RedisTokenDenylist(redis).isDenied("jti-1")).isTrue();
  }

  @Test
  void notDeniedWhenKeyMissing() {
    given(redis.hasKey("deny:jti-2")).willReturn(false);

    assertThat(new RedisTokenDenylist(redis).isDenied("jti-2")).isFalse();
  }
}
