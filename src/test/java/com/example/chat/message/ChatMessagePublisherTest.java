package com.example.chat.message;

import static org.mockito.Mockito.verify;

import com.example.chat.message.dto.MessageResponse;
import com.example.chat.message.dto.RoomEvent;
import com.example.chat.message.dto.RoomEventType;
import com.example.chat.room.dto.RoomResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class ChatMessagePublisherTest {

  @Mock SimpMessagingTemplate messagingTemplate;

  @Test
  void publishesMessageToRoomTopic() {
    MessageResponse message = new MessageResponse(1L, 7L, MessageType.TALK, 1L, "alice", "앨리스",
        "hi", LocalDateTime.now());

    new ChatMessagePublisher(messagingTemplate).publishMessage(message);

    verify(messagingTemplate).convertAndSend("/topic/rooms/7", message);
  }

  @Test
  void publishesRoomEventToLobby() {
    RoomEvent event = new RoomEvent(RoomEventType.ROOM_CREATED,
        new RoomResponse(7L, "general", null, "alice", 1, 0, LocalDateTime.now()));

    new ChatMessagePublisher(messagingTemplate).publishRoomEvent(event);

    verify(messagingTemplate).convertAndSend("/topic/rooms", event);
  }
}
