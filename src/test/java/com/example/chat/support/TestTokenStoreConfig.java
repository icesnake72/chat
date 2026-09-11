package com.example.chat.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// 테스트 클래스패스의 @Configuration은 ChatApplication 컴포넌트 스캔 범위(com.example.chat)라 자동 적용된다.
@Configuration
public class TestTokenStoreConfig {

  @Bean
  @Primary
  public InMemoryTokenDenylist inMemoryTokenDenylist() {
    return new InMemoryTokenDenylist();
  }
}
