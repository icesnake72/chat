package com.example.chat.room;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

  boolean existsByRoomIdAndUserId(Long roomId, Long userId);

  long countByRoomId(Long roomId);

  List<RoomMember> findAllByRoomIdOrderByJoinedAtAsc(Long roomId);

  void deleteByRoomIdAndUserId(Long roomId, Long userId);

  void deleteByRoomId(Long roomId);
}
