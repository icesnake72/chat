package com.example.chat.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class RoomMemberRepositoryTest {

  @Autowired ChatRoomRepository chatRoomRepository;
  @Autowired RoomMemberRepository roomMemberRepository;

  private ChatRoom room;

  @BeforeEach
  void setUp() {
    room = chatRoomRepository.save(new ChatRoom("members", null, 1L, "alice"));
  }

  @Test
  void tracksMembershipPerRoom() {
    roomMemberRepository.save(new RoomMember(room, 1L, "alice"));
    roomMemberRepository.save(new RoomMember(room, 3L, "bob"));

    assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.getId(), 1L)).isTrue();
    assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.getId(), 99L)).isFalse();
    assertThat(roomMemberRepository.countByRoomId(room.getId())).isEqualTo(2);
    assertThat(roomMemberRepository.findAllByRoomIdOrderByJoinedAtAsc(room.getId()))
        .extracting(RoomMember::getUsername).containsExactly("alice", "bob");
  }

  @Test
  void rejectsDuplicateMembership() {
    roomMemberRepository.saveAndFlush(new RoomMember(room, 1L, "alice"));

    assertThatThrownBy(() -> roomMemberRepository.saveAndFlush(new RoomMember(room, 1L, "alice")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletesByRoomAndUser() {
    roomMemberRepository.save(new RoomMember(room, 1L, "alice"));

    roomMemberRepository.deleteByRoomIdAndUserId(room.getId(), 1L);

    assertThat(roomMemberRepository.countByRoomId(room.getId())).isZero();
  }
}
