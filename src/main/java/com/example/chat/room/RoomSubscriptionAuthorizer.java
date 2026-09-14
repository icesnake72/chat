package com.example.chat.room;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.auth.SubscriptionAuthorizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// /topic/rooms/{id} 는 멤버만. 그 외(/topic/rooms 로비, /user/queue/errors)는 인증만 되면 허용.
// ChatRoomService가 아니라 리포지토리를 직접 쓰는 이유: 서비스 → publisher → SimpMessagingTemplate →
// WebSocketConfig → 인터셉터 → 이 클래스로 이어지는 빈 순환 참조를 끊기 위해서다.
@Component
@RequiredArgsConstructor
public class RoomSubscriptionAuthorizer implements SubscriptionAuthorizer {

  private static final Pattern ROOM_TOPIC = Pattern.compile("^/topic/rooms/(\\d+)$");

  private final RoomMemberRepository roomMemberRepository;

  @Override
  public boolean canSubscribe(String destination, ChatPrincipal principal) {
    if (destination == null) {
      return true;
    }
    Matcher m = ROOM_TOPIC.matcher(destination);
    if (!m.matches()) {
      return true;
    }
    return roomMemberRepository.existsByRoomIdAndUserId(Long.valueOf(m.group(1)), principal.userId());
  }
}
