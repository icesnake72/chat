package com.example.chat.room;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.chat.support.TestJwtFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatRoomControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  private final String alice = "Bearer " + TestJwtFactory.token("alice");
  private final String bob = "Bearer " + TestJwtFactory.token("bob");

  private long createRoom(String bearer, String name) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/chat/rooms")
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\",\"description\":\"d\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  }

  private String uniqueName() {
    return "room-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void createsRoomWithOwnerAsFirstMember() throws Exception {
    mockMvc.perform(post("/api/v1/chat/rooms")
            .header(HttpHeaders.AUTHORIZATION, alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + uniqueName() + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ownerUsername").value("alice"))
        .andExpect(jsonPath("$.memberCount").value(1))
        .andExpect(jsonPath("$.onlineCount").value(0));
  }

  @Test
  void rejectsDuplicateNameAndBlankName() throws Exception {
    String name = uniqueName();
    createRoom(alice, name);

    mockMvc.perform(post("/api/v1/chat/rooms")
            .header(HttpHeaders.AUTHORIZATION, bob)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_ROOM_NAME"));

    mockMvc.perform(post("/api/v1/chat/rooms")
            .header(HttpHeaders.AUTHORIZATION, alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void listsRoomsAsPagedModel() throws Exception {
    String name = uniqueName();
    createRoom(alice, name);

    mockMvc.perform(get("/api/v1/chat/rooms").header(HttpHeaders.AUTHORIZATION, alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value(name))
        .andExpect(jsonPath("$.page.size").value(20));
  }

  @Test
  void onlyOwnerCanDelete() throws Exception {
    long id = createRoom(alice, uniqueName());

    mockMvc.perform(delete("/api/v1/chat/rooms/" + id).header(HttpHeaders.AUTHORIZATION, bob))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

    mockMvc.perform(delete("/api/v1/chat/rooms/" + id).header(HttpHeaders.AUTHORIZATION, alice))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/v1/chat/rooms/" + id).header(HttpHeaders.AUTHORIZATION, alice))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
  }

  @Test
  void membersRequireMembershipAndJoinGrantsIt() throws Exception {
    long id = createRoom(alice, uniqueName());

    mockMvc.perform(get("/api/v1/chat/rooms/" + id + "/members")
            .header(HttpHeaders.AUTHORIZATION, bob))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_ROOM_MEMBER"));

    mockMvc.perform(post("/api/v1/chat/rooms/" + id + "/join").header(HttpHeaders.AUTHORIZATION, bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memberCount").value(2));

    mockMvc.perform(get("/api/v1/chat/rooms/" + id + "/members")
            .header(HttpHeaders.AUTHORIZATION, bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].username").value("alice"))
        .andExpect(jsonPath("$[1].username").value("bob"))
        .andExpect(jsonPath("$[1].online").value(false));

    mockMvc.perform(delete("/api/v1/chat/rooms/" + id + "/leave").header(HttpHeaders.AUTHORIZATION, bob))
        .andExpect(status().isNoContent());
  }
}
