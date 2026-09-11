package com.example.chat.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.chat.support.InMemoryTokenDenylist;
import com.example.chat.support.TestJwtFactory;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MeControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired InMemoryTokenDenylist denylist;

  @BeforeEach
  void clearDenylist() {
    denylist.clear();
  }

  @Test
  void returns401WithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/chat/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void returnsMeWithValidToken() throws Exception {
    mockMvc.perform(get("/api/v1/chat/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtFactory.token("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(1))
        .andExpect(jsonPath("$.username").value("alice"))
        .andExpect(jsonPath("$.nickname").value("앨리스"));
  }

  @Test
  void returns401WhenTokenExpired() throws Exception {
    mockMvc.perform(get("/api/v1/chat/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtFactory.expiredToken("alice")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void returns401WhenTokenDenied() throws Exception {
    String token = TestJwtFactory.tokenWithJti("alice", "jti-logout", Duration.ofHours(1));
    denylist.deny("jti-logout");

    mockMvc.perform(get("/api/v1/chat/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void returns401WhenUserUnknownInBoard() throws Exception {
    mockMvc.perform(get("/api/v1/chat/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtFactory.token("ghost")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void healthIsPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
