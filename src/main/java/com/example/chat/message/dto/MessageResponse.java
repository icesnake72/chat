package com.example.chat.message.dto;

import com.example.chat.message.ChatMessage;
import com.example.chat.message.MessageType;
import java.time.LocalDateTime;

public record MessageResponse(
    Long id,
    Long roomId,
    MessageType type,
    Long senderUserId,
    String senderUsername,
    String senderNickname,
    String content,
    LocalDateTime createdAt
) {

  public static MessageResponse from(ChatMessage message) {
    return new MessageResponse(message.getId(), message.getRoom().getId(), message.getType(),
        message.getSenderUserId(), message.getSenderUsername(), message.getSenderNickname(),
        message.getContent(), message.getCreatedAt());
  }
}
