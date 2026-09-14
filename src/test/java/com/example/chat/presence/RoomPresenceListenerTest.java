package com.example.chat.presence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.message.ChatMessageService;
import com.example.chat.message.MessageType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@ExtendWith(MockitoExtension.class)
class RoomPresenceListenerTest {

  @Mock ChatMessageService chatMessageService;

  private final RoomPresenceTracker tracker = new RoomPresenceTracker();
  private final ChatPrincipal alice = new ChatPrincipal(1L, "alice", "앨리스", "jti", Instant.MAX);
  private final Authentication aliceAuth = alice.toAuthentication();

  private RoomPresenceListener listener() {
    return new RoomPresenceListener(tracker, chatMessageService);
  }

  private static Message<byte[]> frame(StompCommand command, String sessionId, String subId, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setSessionId(sessionId);
    if (subId != null) {
      accessor.setSubscriptionId(subId);
    }
    if (destination != null) {
      accessor.setDestination(destination);
    }
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private SessionSubscribeEvent subscribe(String sessionId, String subId, String destination) {
    return new SessionSubscribeEvent(this, frame(StompCommand.SUBSCRIBE, sessionId, subId, destination), aliceAuth);
  }

  @Test
  void firstRoomSubscriptionSendsEnter() {
    listener().onSubscribe(subscribe("s1", "sub-1", "/topic/rooms/7"));

    verify(chatMessageService).system(7L, MessageType.ENTER, alice);
  }

  @Test
  void secondSessionOfSameUserDoesNotRepeatEnter() {
    RoomPresenceListener listener = listener();
    listener.onSubscribe(subscribe("s1", "sub-1", "/topic/rooms/7"));
    listener.onSubscribe(subscribe("s2", "sub-1", "/topic/rooms/7"));

    verify(chatMessageService).system(eq(7L), eq(MessageType.ENTER), any());
  }

  @Test
  void lobbyAndUserQueueSubscriptionsAreIgnored() {
    RoomPresenceListener listener = listener();
    listener.onSubscribe(subscribe("s1", "sub-1", "/topic/rooms"));
    listener.onSubscribe(subscribe("s1", "sub-2", "/user/queue/errors"));

    verify(chatMessageService, never()).system(any(), any(), any());
  }

  @Test
  void lastUnsubscribeSendsLeave() {
    RoomPresenceListener listener = listener();
    listener.onSubscribe(subscribe("s1", "sub-1", "/topic/rooms/7"));

    listener.onUnsubscribe(new SessionUnsubscribeEvent(
        this, frame(StompCommand.UNSUBSCRIBE, "s1", "sub-1", null), aliceAuth));

    verify(chatMessageService).system(7L, MessageType.LEAVE, alice);
  }

  @Test
  void disconnectSendsLeaveForEveryRoom() {
    RoomPresenceListener listener = listener();
    listener.onSubscribe(subscribe("s1", "sub-1", "/topic/rooms/7"));
    listener.onSubscribe(subscribe("s1", "sub-2", "/topic/rooms/8"));

    listener.onDisconnect(new SessionDisconnectEvent(
        this, frame(StompCommand.DISCONNECT, "s1", null, null), "s1", CloseStatus.NORMAL, aliceAuth));

    verify(chatMessageService).system(7L, MessageType.LEAVE, alice);
    verify(chatMessageService).system(8L, MessageType.LEAVE, alice);
  }
}
