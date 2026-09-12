package com.example.chat.message.dto;

import com.example.chat.room.dto.RoomResponse;

// 로비(/topic/rooms) 구독자에게 보내는 방 목록 변화 알림
public record RoomEvent(RoomEventType type, RoomResponse room) {
}
