package com.example.chat.presence;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.message.ChatMessageService;
import com.example.chat.message.MessageType;
import java.security.Principal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

// Spring이 STOMP 프레임 처리 후 발행하는 세션 이벤트를 듣는다. 인터셉터에서 거부된 SUBSCRIBE는
// 이벤트가 발행되지 않으므로(preSend 예외 → send 실패) 여기서는 멤버십을 다시 검사하지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomPresenceListener {

  private static final Pattern ROOM_TOPIC = Pattern.compile("^/topic/rooms/(\\d+)$");

  private final RoomPresenceTracker tracker;
  private final ChatMessageService chatMessageService;

  @EventListener
  public void onSubscribe(SessionSubscribeEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    Optional<Long> roomId = roomIdOf(accessor.getDestination());
    Optional<ChatPrincipal> user = ChatPrincipal.from(event.getUser());
    if (roomId.isEmpty() || user.isEmpty()) {
      return;
    }
    boolean first = tracker.subscribe(
        accessor.getSessionId(), accessor.getSubscriptionId(), roomId.get(), user.get().username());
    if (first) {
      chatMessageService.system(roomId.get(), MessageType.ENTER, user.get());
    }
  }

  @EventListener
  public void onUnsubscribe(SessionUnsubscribeEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    tracker.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId())
        .filter(RoomPresenceTracker.Presence::lastForUser)
        .ifPresent(presence -> leave(presence, event.getUser()));
  }

  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    tracker.disconnect(event.getSessionId()).stream()
        .filter(RoomPresenceTracker.Presence::lastForUser)
        .forEach(presence -> leave(presence, event.getUser()));
  }

  private void leave(RoomPresenceTracker.Presence presence, Principal user) {
    ChatPrincipal.from(user)
        .ifPresent(p -> chatMessageService.system(presence.roomId(), MessageType.LEAVE, p));
  }

  static Optional<Long> roomIdOf(String destination) {
    if (destination == null) {
      return Optional.empty();
    }
    Matcher m = ROOM_TOPIC.matcher(destination);
    return m.matches() ? Optional.of(Long.valueOf(m.group(1))) : Optional.empty();
  }
}
