package com.example.chat.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.global.exception.BusinessException;
import com.example.chat.global.exception.ErrorCode;
import com.example.chat.global.exception.ForbiddenException;
import com.example.chat.global.exception.NotFoundException;
import com.example.chat.message.dto.MessagePageResponse;
import com.example.chat.message.dto.MessageResponse;
import com.example.chat.room.ChatRoomService;
import com.example.chat.room.dto.RoomCreateRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ChatMessageServiceTest {

  @Autowired ChatMessageService chatMessageService;
  @Autowired ChatRoomService chatRoomService;
  @MockitoBean ChatMessagePublisher publisher;

  private final ChatPrincipal alice = new ChatPrincipal(1L, "alice", "앨리스", "jti-a", Instant.MAX);
  private final ChatPrincipal bob = new ChatPrincipal(3L, "bob", "밥", "jti-b", Instant.MAX);

  private Long roomId;

  @BeforeEach
  void setUp() {
    roomId = chatRoomService.create(
        new RoomCreateRequest("svc-" + UUID.randomUUID().toString().substring(0, 8), null), alice).id();
  }

  @Test
  void sendSavesSnapshotAndPublishes() {
    MessageResponse response = chatMessageService.send(roomId, alice, "  hello  ");

    assertThat(response.id()).isNotNull();
    assertThat(response.roomId()).isEqualTo(roomId);
    assertThat(response.type()).isEqualTo(MessageType.TALK);
    assertThat(response.senderNickname()).isEqualTo("앨리스");
    assertThat(response.content()).isEqualTo("hello");
    assertThat(response.createdAt()).isNotNull();
    verify(publisher).publishMessage(any(MessageResponse.class));
  }

  @Test
  void sendRejectsBlankAndTooLong() {
    assertThatThrownBy(() -> chatMessageService.send(roomId, alice, "   "))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    assertThatThrownBy(() -> chatMessageService.send(roomId, alice, "x".repeat(1001)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.MESSAGE_TOO_LONG);
  }

  @Test
  void sendRequiresMembershipAndExistingRoom() {
    assertThatThrownBy(() -> chatMessageService.send(roomId, bob, "hi"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> chatMessageService.send(999_999L, alice, "hi"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void systemMessageRendersNickname() {
    MessageResponse enter = chatMessageService.system(roomId, MessageType.ENTER, alice);

    assertThat(enter.type()).isEqualTo(MessageType.ENTER);
    assertThat(enter.content()).isEqualTo("앨리스님이 입장했습니다.");
  }

  @Test
  void historyPagesByKeysetOldestFirst() {
    for (int i = 1; i <= 7; i++) {
      chatMessageService.send(roomId, alice, "m" + i);
    }

    MessagePageResponse first = chatMessageService.history(roomId, alice, null, 3);
    assertThat(first.messages()).extracting(MessageResponse::content).containsExactly("m5", "m6", "m7");
    assertThat(first.hasMore()).isTrue();
    assertThat(first.nextBefore()).isEqualTo(first.messages().get(0).id());

    MessagePageResponse second = chatMessageService.history(roomId, alice, first.nextBefore(), 3);
    assertThat(second.messages()).extracting(MessageResponse::content).containsExactly("m2", "m3", "m4");
    assertThat(second.hasMore()).isTrue();

    MessagePageResponse third = chatMessageService.history(roomId, alice, second.nextBefore(), 3);
    assertThat(third.messages()).extracting(MessageResponse::content).containsExactly("m1");
    assertThat(third.hasMore()).isFalse();
  }

  @Test
  void historyRequiresMembership() {
    assertThatThrownBy(() -> chatMessageService.history(roomId, bob, null, 10))
        .isInstanceOf(ForbiddenException.class);
  }
}
