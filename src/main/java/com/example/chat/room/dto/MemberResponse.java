package com.example.chat.room.dto;

import com.example.chat.room.RoomMember;
import java.time.LocalDateTime;

public record MemberResponse(Long userId, String username, boolean online, LocalDateTime joinedAt) {

  public static MemberResponse of(RoomMember member, boolean online) {
    return new MemberResponse(member.getUserId(), member.getUsername(), online, member.getJoinedAt());
  }
}
