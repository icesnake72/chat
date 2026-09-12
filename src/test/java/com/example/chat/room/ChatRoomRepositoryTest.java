package com.example.chat.room;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ChatRoomRepositoryTest {

  @Autowired ChatRoomRepository chatRoomRepository;

  @Test
  void savesWithAuditingTimestamps() {
    ChatRoom room = chatRoomRepository.save(new ChatRoom("general", "잡담", 1L, "alice"));

    assertThat(room.getId()).isNotNull();
    assertThat(room.getCreatedAt()).isNotNull();
    assertThat(room.getUpdatedAt()).isNotNull();
    assertThat(chatRoomRepository.existsByName("general")).isTrue();
    assertThat(chatRoomRepository.existsByName("nope")).isFalse();
  }

  @Test
  void listsNewestFirst() {
    chatRoomRepository.save(new ChatRoom("first", null, 1L, "alice"));
    chatRoomRepository.save(new ChatRoom("second", null, 1L, "alice"));

    var page = chatRoomRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10));

    assertThat(page.getContent()).extracting(ChatRoom::getName).containsSubsequence("second", "first");
  }
}
