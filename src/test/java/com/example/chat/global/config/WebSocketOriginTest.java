package com.example.chat.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chat.support.TestJwtFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

// 핸드셰이크 Origin 검사 + heartbeat 협상. 기본 패턴은 포트 없는 http://localhost (caddy 경유)도 허용해야 한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.ws.allowed-origin-patterns=http://localhost,http://localhost:*")
class WebSocketOriginTest {

  @LocalServerPort int port;

  private CompletableFuture<StompHeaders> connect(String origin) {
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
    handshake.setOrigin(origin);
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add("Authorization", "Bearer " + TestJwtFactory.token("alice"));
    CompletableFuture<StompHeaders> connected = new CompletableFuture<>();
    client.connectAsync("ws://localhost:" + port + "/ws", handshake, connectHeaders,
        new StompSessionHandlerAdapter() {
          @Override
          public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.complete(connectedHeaders);
            session.disconnect();
          }
        }).whenComplete((session, ex) -> {
          if (ex != null) {
            connected.completeExceptionally(ex);
          }
        });
    return connected;
  }

  @Test
  void allowsOriginWithoutPortAndNegotiatesHeartbeat() throws Exception {
    StompHeaders headers = connect("http://localhost").get(5, TimeUnit.SECONDS);

    assertThat(headers.getFirst("heart-beat")).isEqualTo("10000,10000");
  }

  @Test
  void allowsOriginWithAnyLocalhostPort() throws Exception {
    assertThat(connect("http://localhost:5173").get(5, TimeUnit.SECONDS)).isNotNull();
  }

  @Test
  void rejectsForeignOriginAtHandshake() {
    assertThatThrownBy(() -> connect("http://evil.example").get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class);
  }
}
