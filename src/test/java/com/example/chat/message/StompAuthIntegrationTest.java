package com.example.chat.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chat.message.dto.EchoRequest;
import com.example.chat.message.dto.EchoResponse;
import com.example.chat.support.InMemoryTokenDenylist;
import com.example.chat.support.TestJwtFactory;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompAuthIntegrationTest {

  @LocalServerPort int port;
  @Autowired InMemoryTokenDenylist denylist;

  private WebSocketStompClient client;
  private StompSession session;

  @BeforeEach
  void setUp() {
    denylist.clear();
    client = new WebSocketStompClient(new StandardWebSocketClient());
    // ERROR 프레임은 text/plain, 데이터 프레임은 JSON — 둘 다 받으려고 composite
    client.setMessageConverter(new CompositeMessageConverter(
        List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
  }

  @AfterEach
  void tearDown() {
    if (session != null && session.isConnected()) {
      session.disconnect();
    }
  }

  private String url() {
    return "ws://localhost:" + port + "/ws";
  }

  private StompHeaders bearer(String token) {
    StompHeaders headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + token);
    return headers;
  }

  // CONNECTED 전에 오는 ERROR 프레임을 잡는다 (DefaultStompSession이 handleFrame으로 넘겨준다)
  static class ErrorCapture extends StompSessionHandlerAdapter {
    final CompletableFuture<StompHeaders> error = new CompletableFuture<>();

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      error.complete(headers);
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
      // 서버가 ERROR 후 연결을 닫으면 여기로도 온다 — ERROR 캡처가 우선이므로 무시
    }
  }

  @Test
  void connectsWithValidTokenAndReceivesEcho() throws Exception {
    session = client.connectAsync(url(), new WebSocketHttpHeaders(),
        bearer(TestJwtFactory.token("alice")), new StompSessionHandlerAdapter() {})
        .get(5, TimeUnit.SECONDS);

    CompletableFuture<EchoResponse> received = new CompletableFuture<>();
    session.subscribe("/topic/echo", new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return EchoResponse.class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        received.complete((EchoResponse) payload);
      }
    });
    Thread.sleep(300); // 인바운드 채널이 SUBSCRIBE를 처리할 시간 (simple broker는 receipt를 보내지 않는다)
    session.send("/app/echo", new EchoRequest("hello"));

    EchoResponse response = received.get(5, TimeUnit.SECONDS);
    assertThat(response.sender()).isEqualTo("alice");
    assertThat(response.content()).isEqualTo("hello");
    assertThat(response.sentAt()).isNotNull();
  }

  @Test
  void rejectsConnectWithoutToken() throws Exception {
    ErrorCapture capture = new ErrorCapture();

    client.connectAsync(url(), new WebSocketHttpHeaders(), new StompHeaders(), capture);

    StompHeaders error = capture.error.get(5, TimeUnit.SECONDS);
    assertThat(error.getFirst("code")).isEqualTo("LOGIN_REQUIRED");
  }

  @Test
  void rejectsConnectWithExpiredTokenAsTokenExpired() throws Exception {
    ErrorCapture capture = new ErrorCapture();

    client.connectAsync(url(), new WebSocketHttpHeaders(),
        bearer(TestJwtFactory.expiredToken("alice")), capture);

    StompHeaders error = capture.error.get(5, TimeUnit.SECONDS);
    assertThat(error.getFirst("code")).isEqualTo("TOKEN_EXPIRED");
  }

  @Test
  void rejectsConnectWhenJtiDenied() throws Exception {
    String token = TestJwtFactory.tokenWithJti("alice", "jti-ws-logout", Duration.ofHours(1));
    denylist.deny("jti-ws-logout");
    ErrorCapture capture = new ErrorCapture();

    client.connectAsync(url(), new WebSocketHttpHeaders(), bearer(token), capture);

    StompHeaders error = capture.error.get(5, TimeUnit.SECONDS);
    assertThat(error.getFirst("code")).isEqualTo("LOGIN_REQUIRED");
  }

  @Test
  void rejectsSendAfterLogout() throws Exception {
    String token = TestJwtFactory.tokenWithJti("alice", "jti-live", Duration.ofHours(1));
    ErrorCapture capture = new ErrorCapture();
    session = client.connectAsync(url(), new WebSocketHttpHeaders(), bearer(token), capture)
        .get(5, TimeUnit.SECONDS);

    denylist.deny("jti-live"); // board 로그아웃을 흉내낸다
    session.send("/app/echo", new EchoRequest("after logout"));

    StompHeaders error = capture.error.get(5, TimeUnit.SECONDS);
    assertThat(error.getFirst("code")).isEqualTo("LOGIN_REQUIRED");
  }
}
