package com.example.chat.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.chat.room.ChatRoom;
import com.example.chat.room.ChatRoomRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ChatMessageRepositoryTest {

  @Autowired ChatRoomRepository chatRoomRepository;
  @Autowired ChatMessageRepository chatMessageRepository;

  private ChatRoom room;

  @BeforeEach
  void setUp() {
    room = chatRoomRepository.save(new ChatRoom("history", null, 1L, "alice"));
    for (int i = 1; i <= 5; i++) {
      chatMessageRepository.save(
          new ChatMessage(room, MessageType.TALK, 1L, "alice", "앨리스", "m" + i));
    }
  }

  @Test
  void firstPageIsNewestFirst() {
    List<ChatMessage> page =
        chatMessageRepository.findByRoomIdOrderByIdDesc(room.getId(), PageRequest.of(0, 2));

    assertThat(page).extracting(ChatMessage::getContent).containsExactly("m5", "m4");
    assertThat(page.get(0).getCreatedAt()).isNotNull();
  }

  @Test
  void keysetPageContinuesBelowCursor() {
    List<ChatMessage> first =
        chatMessageRepository.findByRoomIdOrderByIdDesc(room.getId(), PageRequest.of(0, 2));
    Long cursor = first.get(1).getId();

    List<ChatMessage> next = chatMessageRepository
        .findByRoomIdAndIdLessThanOrderByIdDesc(room.getId(), cursor, PageRequest.of(0, 2));

    assertThat(next).extracting(ChatMessage::getContent).containsExactly("m3", "m2");
  }
}
