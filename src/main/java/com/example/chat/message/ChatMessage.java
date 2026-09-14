package com.example.chat.message;

import com.example.chat.room.ChatRoom;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 발신자 정보는 발신 당시 스냅샷(닉네임 변경에 영향받지 않음). 수정이 없으므로 updatedAt이 없다.
// (room_id, id) 인덱스는 keyset 페이징(WHERE room_id=? AND id<? ORDER BY id DESC)을 위한 것.
@Entity
@Table(name = "chat_messages", indexes = @Index(
    name = "idx_chat_messages_room_id_id", columnList = "room_id, id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id", nullable = false)
  private ChatRoom room;

  @Column(name = "sender_user_id")
  private Long senderUserId;

  @Column(name = "sender_username", nullable = false, length = 50)
  private String senderUsername;

  @Column(name = "sender_nickname", nullable = false, length = 50)
  private String senderNickname;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private MessageType type;

  @Column(nullable = false, length = 1000)
  private String content;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public ChatMessage(ChatRoom room, MessageType type, Long senderUserId,
      String senderUsername, String senderNickname, String content) {
    this.room = room;
    this.type = type;
    this.senderUserId = senderUserId;
    this.senderUsername = senderUsername;
    this.senderNickname = senderNickname;
    this.content = content;
  }
}
