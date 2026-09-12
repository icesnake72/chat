package com.example.chat.room;

import com.example.chat.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// board 사용자와는 FK 없이 id·username 스냅샷만 둔다 (board 스키마 무수정, 읽기 전용 경계).
@Entity
@Table(name = "chat_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 50)
  private String name;

  @Column(length = 200)
  private String description;

  @Column(name = "owner_user_id", nullable = false)
  private Long ownerUserId;

  @Column(name = "owner_username", nullable = false, length = 50)
  private String ownerUsername;

  public ChatRoom(String name, String description, Long ownerUserId, String ownerUsername) {
    this.name = name;
    this.description = description;
    this.ownerUserId = ownerUserId;
    this.ownerUsername = ownerUsername;
  }

  public boolean isOwnedBy(Long userId) {
    return ownerUserId.equals(userId);
  }
}
