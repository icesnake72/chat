package com.example.chat.auth;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// 토큰에는 username뿐이라 userId·nickname은 board 스키마에서 읽는다. JPA 엔티티로 매핑하지 않는 이유:
// board의 ddl-auto가 관리하는 테이블을 chat의 Hibernate가 검증·변경하지 않게 하기 위함 (읽기 전용 경계).
@Component
public class BoardUserReader {

  private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");

  private final JdbcClient jdbcClient;
  private final String sql;

  public BoardUserReader(JdbcClient jdbcClient, @Value("${app.board.schema}") String schema) {
    if (!SAFE_SCHEMA.matcher(schema).matches()) {
      throw new IllegalArgumentException("invalid board schema name: " + schema);
    }
    this.jdbcClient = jdbcClient;
    this.sql = """
        SELECT u.id, u.username, p.nickname
        FROM %1$s.users u
        JOIN %1$s.user_profiles p ON p.user_id = u.id
        WHERE u.username = :username
        """.formatted(schema);
  }

  public Optional<BoardUser> findByUsername(String username) {
    return jdbcClient.sql(sql)
        .param("username", username)
        .query((rs, rowNum) -> new BoardUser(
            rs.getLong("id"), rs.getString("username"), rs.getString("nickname")))
        .optional();
  }
}
