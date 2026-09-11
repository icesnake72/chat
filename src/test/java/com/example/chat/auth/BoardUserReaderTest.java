package com.example.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class BoardUserReaderTest {

  @Autowired BoardUserReader reader;
  @Autowired JdbcClient jdbcClient;

  @Test
  void findsUserWithProfile() {
    Optional<BoardUser> user = reader.findByUsername("alice");

    assertThat(user).isPresent();
    assertThat(user.get().id()).isEqualTo(1L);
    assertThat(user.get().nickname()).isEqualTo("앨리스");
  }

  @Test
  void emptyWhenUnknownUsername() {
    assertThat(reader.findByUsername("nobody")).isEmpty();
  }

  @Test
  void emptyWhenProfileMissing() {
    assertThat(reader.findByUsername("noprofile")).isEmpty();
  }

  @Test
  void rejectsUnsafeSchemaName() {
    assertThatThrownBy(() -> new BoardUserReader(jdbcClient, "board; DROP TABLE x"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
