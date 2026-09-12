package com.example.chat.message;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.room.ChatRoomService;
import com.example.chat.room.dto.RoomCreateRequest;
import com.example.chat.support.TestJwtFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessageHistoryControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired ChatRoomService chatRoomService;
  @Autowired ChatMessageService chatMessageService;
  @MockitoBean ChatMessagePublisher publisher;

  private final ChatPrincipal alicePrincipal =
      new ChatPrincipal(1L, "alice", "앨리스", "jti-a", Instant.MAX);
  private final String alice = "Bearer " + TestJwtFactory.token("alice");
  private final String bob = "Bearer " + TestJwtFactory.token("bob");

  private Long roomId;

  @BeforeEach
  void setUp() {
    roomId = chatRoomService.create(
        new RoomCreateRequest("hist-" + UUID.randomUUID().toString().substring(0, 8), null),
        alicePrincipal).id();
    for (int i = 1; i <= 3; i++) {
      chatMessageService.send(roomId, alicePrincipal, "m" + i);
    }
  }

  @Test
  void returnsHistoryOldestFirstForMember() throws Exception {
    mockMvc.perform(get("/api/v1/chat/rooms/" + roomId + "/messages")
            .header(HttpHeaders.AUTHORIZATION, alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(3))
        .andExpect(jsonPath("$.messages[0].content").value("m1"))
        .andExpect(jsonPath("$.messages[2].content").value("m3"))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  void pagesWithSizeAndBefore() throws Exception {
    mockMvc.perform(get("/api/v1/chat/rooms/" + roomId + "/messages?size=2")
            .header(HttpHeaders.AUTHORIZATION, alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(2))
        .andExpect(jsonPath("$.messages[0].content").value("m2"))
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.nextBefore").isNumber());
  }

  @Test
  void rejectsNonMemberAndUnknownRoom() throws Exception {
    mockMvc.perform(get("/api/v1/chat/rooms/" + roomId + "/messages")
            .header(HttpHeaders.AUTHORIZATION, bob))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_ROOM_MEMBER"));

    mockMvc.perform(get("/api/v1/chat/rooms/999999/messages")
            .header(HttpHeaders.AUTHORIZATION, alice))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
  }
}
