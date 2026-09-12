package com.example.chat.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// "입장한 상태"를 영속화한다. 접속 여부(온라인)는 RoomPresenceTracker가 메모리로 따로 관리한다.
@Entity
@Table(name = "room_members", uniqueConstraints = @UniqueConstraint(
    name = "uk_room_members_room_user", columnNames = {"room_id", "user_id"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomMember {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id", nullable = false)
  private ChatRoom room;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(nullable = false, length = 50)
  private String username;

  @CreatedDate
  @Column(name = "joined_at", nullable = false, updatable = false)
  private LocalDateTime joinedAt;

  public RoomMember(ChatRoom room, Long userId, String username) {
    this.room = room;
    this.userId = userId;
    this.username = username;
  }
}
