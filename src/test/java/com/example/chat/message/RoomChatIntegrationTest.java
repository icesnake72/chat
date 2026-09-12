package com.example.chat.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.global.exception.ErrorResponse;
import com.example.chat.message.dto.MessageResponse;
import com.example.chat.message.dto.SendMessageRequest;
import com.example.chat.room.ChatRoomService;
import com.example.chat.room.dto.RoomCreateRequest;
import com.example.chat.support.InMemoryTokenDenylist;
import com.example.chat.support.TestJwtFactory;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
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

// 실제 서버를 띄우고 두 사용자(alice, bob)가 STOMP로 방에 들어와 대화하는 시나리오.
// 인증 거부 3종은 1일차 echo 테스트에서 옮겨 왔다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomChatIntegrationTest {

  @LocalServerPort int port;
  @Autowired InMemoryTokenDenylist denylist;
  @Autowired ChatRoomService chatRoomService;
  @Autowired ChatMessageRepository chatMessageRepository;

  private final ChatPrincipal alicePrincipal =
      new ChatPrincipal(1L, "alice", "앨리스", "jti-a", Instant.MAX);
  private final ChatPrincipal bobPrincipal =
      new ChatPrincipal(3L, "bob", "밥", "jti-b", Instant.MAX);

  private WebSocketStompClient client;
  private final List<StompSession> sessions = new ArrayList<>();

  @BeforeEach
  void setUp() {
    denylist.clear();
    client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new CompositeMessageConverter(
        List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
  }

  @AfterEach
  void tearDown() {
    for (StompSession session : sessions) {
      if (session.isConnected()) {
        try {
          session.disconnect();
        } catch (RuntimeException e) {
          // 서버가 ERROR 후 먼저 닫은 세션 — 정리 목적이므로 무시
        }
      }
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

  private StompSession connect(String username, StompSessionHandlerAdapter handler) throws Exception {
    StompSession session = client.connectAsync(url(), new WebSocketHttpHeaders(),
        bearer(TestJwtFactory.token(username)), handler).get(5, TimeUnit.SECONDS);
    sessions.add(session);
    return session;
  }

  private Long newRoom() {
    return chatRoomService.create(
        new RoomCreateRequest("it-" + UUID.randomUUID().toString().substring(0, 8), null),
        alicePrincipal).id();
  }

  // 방 토픽 구독 → 받은 MessageResponse를 큐에 쌓는다
  private BlockingQueue<MessageResponse> subscribeRoom(StompSession session, Long roomId) throws Exception {
    BlockingQueue<MessageResponse> queue = new LinkedBlockingQueue<>();
    session.subscribe("/topic/rooms/" + roomId, new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return MessageResponse.class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        queue.offer((MessageResponse) payload);
      }
    });
    Thread.sleep(300); // 인바운드 채널이 SUBSCRIBE를 브로커에 등록할 시간
    return queue;
  }

  private static MessageResponse await(BlockingQueue<MessageResponse> queue,
      Predicate<MessageResponse> matcher) throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      MessageResponse m = queue.poll(500, TimeUnit.MILLISECONDS);
      if (m != null && matcher.test(m)) {
        return m;
      }
    }
    throw new AssertionError("expected message did not arrive");
  }

  // CONNECTED 전후 ERROR 프레임을 잡는다
  static class ErrorCapture extends StompSessionHandlerAdapter {
    final CompletableFuture<StompHeaders> error = new CompletableFuture<>();

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      error.complete(headers);
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
    }
  }

  @Test
  void rejectsConnectWithoutTokenExpiredTokenAndDeniedToken() throws Exception {
    ErrorCapture noToken = new ErrorCapture();
    client.connectAsync(url(), new WebSocketHttpHeaders(), new StompHeaders(), noToken);
    assertThat(noToken.error.get(5, TimeUnit.SECONDS).getFirst("code")).isEqualTo("LOGIN_REQUIRED");

    ErrorCapture expired = new ErrorCapture();
    client.connectAsync(url(), new WebSocketHttpHeaders(),
        bearer(TestJwtFactory.expiredToken("alice")), expired);
    assertThat(expired.error.get(5, TimeUnit.SECONDS).getFirst("code")).isEqualTo("TOKEN_EXPIRED");

    denylist.deny("jti-ws-logout");
    ErrorCapture denied = new ErrorCapture();
    client.connectAsync(url(), new WebSocketHttpHeaders(),
        bearer(TestJwtFactory.tokenWithJti("alice", "jti-ws-logout", Duration.ofHours(1))), denied);
    assertThat(denied.error.get(5, TimeUnit.SECONDS).getFirst("code")).isEqualTo("LOGIN_REQUIRED");
  }

  @Test
  void memberSeesEnterTalkAndLeaveOfAnotherMember() throws Exception {
    Long roomId = newRoom();
    chatRoomService.join(roomId, bobPrincipal);

    StompSession alice = connect("alice", new StompSessionHandlerAdapter() {});
    BlockingQueue<MessageResponse> aliceInbox = subscribeRoom(alice, roomId);

    StompSession bob = connect("bob", new StompSessionHandlerAdapter() {});
    subscribeRoom(bob, roomId);

    MessageResponse enter = await(aliceInbox,
        m -> m.type() == MessageType.ENTER && m.senderUsername().equals("bob"));
    assertThat(enter.content()).isEqualTo("밥님이 입장했습니다.");

    bob.send("/app/rooms/" + roomId + "/messages", new SendMessageRequest("hi alice"));
    MessageResponse talk = await(aliceInbox, m -> m.type() == MessageType.TALK);
    assertThat(talk.senderNickname()).isEqualTo("밥");
    assertThat(talk.content()).isEqualTo("hi alice");
    assertThat(chatMessageRepository.findByRoomIdOrderByIdDesc(roomId, PageRequest.of(0, 10)))
        .extracting(ChatMessage::getContent).contains("hi alice", "밥님이 입장했습니다.");

    bob.disconnect();
    MessageResponse leave = await(aliceInbox, m -> m.type() == MessageType.LEAVE);
    assertThat(leave.senderUsername()).isEqualTo("bob");
  }

  @Test
  void nonMemberSubscriptionIsRejectedWithErrorFrame() throws Exception {
    Long roomId = newRoom(); // bob은 join하지 않았다
    ErrorCapture capture = new ErrorCapture();
    StompSession bob = connect("bob", capture);

    bob.subscribe("/topic/rooms/" + roomId, new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return String.class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
      }
    });

    assertThat(capture.error.get(5, TimeUnit.SECONDS).getFirst("code")).isEqualTo("NOT_ROOM_MEMBER");
  }

  @Test
  void invalidMessageGoesToUserErrorQueueWithoutDisconnecting() throws Exception {
    Long roomId = newRoom();
    StompSession alice = connect("alice", new StompSessionHandlerAdapter() {});
    BlockingQueue<MessageResponse> inbox = subscribeRoom(alice, roomId);
    CompletableFuture<ErrorResponse> error = new CompletableFuture<>();
    alice.subscribe("/user/queue/errors", new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return ErrorResponse.class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        error.complete((ErrorResponse) payload);
      }
    });
    Thread.sleep(300);

    alice.send("/app/rooms/" + roomId + "/messages", new SendMessageRequest("   "));
    assertThat(error.get(5, TimeUnit.SECONDS).code()).isEqualTo("INVALID_INPUT");

    alice.send("/app/rooms/" + roomId + "/messages", new SendMessageRequest("still here"));
    assertThat(await(inbox, m -> m.type() == MessageType.TALK).content()).isEqualTo("still here");
  }
}
