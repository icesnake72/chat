package com.example.chat.global.config;

import com.example.chat.auth.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// 내장 simple broker (단일 인스턴스). 브로커 교체가 필요하면 이 클래스와 ChatMessagePublisher만 바꾼다.
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private static final long HEARTBEAT_MS = 10_000;

  private final StompAuthChannelInterceptor authInterceptor;
  private final StompErrorHandler errorHandler;
  private final String[] allowedOriginPatterns;

  public WebSocketConfig(
      StompAuthChannelInterceptor authInterceptor,
      StompErrorHandler errorHandler,
      @Value("${app.ws.allowed-origin-patterns}") String[] allowedOriginPatterns) {
    this.authInterceptor = authInterceptor;
    this.errorHandler = errorHandler;
    this.allowedOriginPatterns = allowedOriginPatterns;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.setErrorHandler(errorHandler);
    // 네이티브 WebSocket만 (SockJS 없음). 핸드셰이크는 공개, 인증은 CONNECT 프레임에서.
    registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOriginPatterns);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    // heartbeat 10s/10s — 클라이언트가 끊긴 연결을 감지해 재연결하려면 서버가 주기적으로 신호를 보내야 한다.
    // simple broker의 heartbeat는 TaskScheduler가 있어야 켜진다.
    registry.enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
        .setTaskScheduler(heartbeatScheduler());
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authInterceptor);
  }

  // Boot의 기본 TaskScheduler 빈은 @EnableScheduling 없이는 만들어지지 않아 전용으로 둔다.
  @Bean
  public ThreadPoolTaskScheduler heartbeatScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("ws-heartbeat-");
    scheduler.initialize();
    return scheduler;
  }
}
