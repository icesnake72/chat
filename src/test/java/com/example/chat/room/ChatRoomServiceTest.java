package com.example.chat.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.global.exception.DuplicateException;
import com.example.chat.global.exception.ForbiddenException;
import com.example.chat.global.exception.NotFoundException;
import com.example.chat.message.ChatMessagePublisher;
import com.example.chat.message.dto.RoomEventType;
import com.example.chat.room.dto.RoomCreateRequest;
import com.example.chat.room.dto.RoomResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ChatRoomServiceTest {

  @Autowired ChatRoomService chatRoomService;
  @Autowired RoomMemberRepository roomMemberRepository;
  @MockitoBean ChatMessagePublisher publisher;

  private final ChatPrincipal alice = new ChatPrincipal(1L, "alice", "앨리스", "jti-a", Instant.MAX);
  private final ChatPrincipal bob = new ChatPrincipal(3L, "bob", "밥", "jti-b", Instant.MAX);

  @Test
  void createAddsOwnerAsMemberAndPublishesEvent() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("general", "잡담"), alice);

    assertThat(room.ownerUsername()).isEqualTo("alice");
    assertThat(room.memberCount()).isEqualTo(1);
    assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.id(), 1L)).isTrue();
    verify(publisher).publishRoomEvent(argThat(e -> e.type() == RoomEventType.ROOM_CREATED));
  }

  @Test
  void createRejectsDuplicateName() {
    chatRoomService.create(new RoomCreateRequest("dup", null), alice);

    assertThatThrownBy(() -> chatRoomService.create(new RoomCreateRequest("dup", null), bob))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void joinIsIdempotentAndPublishesOnce() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("join", null), alice);

    chatRoomService.join(room.id(), bob);
    RoomResponse again = chatRoomService.join(room.id(), bob);

    assertThat(again.memberCount()).isEqualTo(2);
    verify(publisher, times(1))
        .publishRoomEvent(argThat(e -> e.type() == RoomEventType.MEMBER_COUNT));
  }

  @Test
  void leaveRemovesMembership() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("leave", null), alice);
    chatRoomService.join(room.id(), bob);

    chatRoomService.leave(room.id(), bob);
    chatRoomService.leave(room.id(), bob); // 멱등

    assertThat(chatRoomService.get(room.id()).memberCount()).isEqualTo(1);
  }

  @Test
  void membersRequiresMembership() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("members", null), alice);

    assertThat(chatRoomService.members(room.id(), alice))
        .extracting("username").containsExactly("alice");
    assertThatThrownBy(() -> chatRoomService.members(room.id(), bob))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void deleteRemovesRoomAndPublishes() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("delete", null), alice);

    chatRoomService.delete(room.id());

    assertThatThrownBy(() -> chatRoomService.get(room.id())).isInstanceOf(NotFoundException.class);
    assertThat(roomMemberRepository.countByRoomId(room.id())).isZero();
    verify(publisher).publishRoomEvent(argThat(e -> e.type() == RoomEventType.ROOM_DELETED));
  }

  @Test
  void listIsNewestFirst() {
    chatRoomService.create(new RoomCreateRequest("older", null), alice);
    chatRoomService.create(new RoomCreateRequest("newer", null), alice);

    assertThat(chatRoomService.list(PageRequest.of(0, 10)).getContent())
        .extracting(RoomResponse::name).containsSubsequence("newer", "older");
  }
}
