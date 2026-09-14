package com.example.chat.room;

import com.example.chat.auth.ChatPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// @PreAuthorize("@roomSecurity.isOwner(#id, principal)") 에서 SpEL로 호출되는 빈.
// 방이 없으면 true를 돌려 서비스가 404를 내게 한다 (403보다 정확한 응답).
@Component("roomSecurity")
@RequiredArgsConstructor
public class RoomSecurity {

  private final ChatRoomRepository chatRoomRepository;

  public boolean isOwner(Long roomId, ChatPrincipal principal) {
    return chatRoomRepository.findById(roomId)
        .map(room -> room.isOwnedBy(principal.userId()))
        .orElse(true);
  }
}
