package com.example.chat.message;

import com.example.chat.message.dto.MessageResponse;
import com.example.chat.message.dto.RoomEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// 브로커에 쓰는 유일한 곳. 나중에 Redis 백플레인으로 바꿔도 서비스 코드는 손대지 않는다.
@Component
@RequiredArgsConstructor
public class ChatMessagePublisher {

  public static final String LOBBY_TOPIC = "/topic/rooms";

  private final SimpMessagingTemplate messagingTemplate;

  public static String roomTopic(Long roomId) {
    return "/topic/rooms/" + roomId;
  }

  public void publishMessage(MessageResponse message) {
    messagingTemplate.convertAndSend(roomTopic(message.roomId()), message);
  }

  public void publishRoomEvent(RoomEvent event) {
    messagingTemplate.convertAndSend(LOBBY_TOPIC, event);
  }
}
