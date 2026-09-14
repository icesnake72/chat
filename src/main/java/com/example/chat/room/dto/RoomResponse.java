package com.example.chat.room.dto;

import com.example.chat.room.ChatRoom;
import java.time.LocalDateTime;

public record RoomResponse(
    Long id,
    String name,
    String description,
    String ownerUsername,
    long memberCount,
    int onlineCount,
    LocalDateTime createdAt
) {

  public static RoomResponse of(ChatRoom room, long memberCount, int onlineCount) {
    return new RoomResponse(room.getId(), room.getName(), room.getDescription(),
        room.getOwnerUsername(), memberCount, onlineCount, room.getCreatedAt());
  }
}
