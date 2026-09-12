# 2일차 방 도메인 + 메시지 영속화 + presence 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 채팅방 REST(생성·목록·입장·퇴장·삭제·멤버·이력)와 방 단위 STOMP 메시지 영속화, 입장·퇴장 presence를 완성해 1일차 echo를 대체한다.

**Architecture:** 엔티티(`ChatRoom`, `RoomMember`, `ChatMessage`) → 리포지토리 → DTO → `ChatMessagePublisher`(브로커 접근 단일 지점) → 서비스 → 컨트롤러 순으로 쌓는다. 인터셉터는 `SubscriptionAuthorizer` 인터페이스로 방 멤버십을 묻고(auth → room 역방향 의존 방지), presence는 Spring의 세션 이벤트(`SessionSubscribeEvent` 등)를 듣는 리스너가 `RoomPresenceTracker`에 반영한다.

**Tech Stack:** Spring Boot 4.1.1, Spring Data JPA (Auditing), Spring Messaging (`SimpMessagingTemplate`, `@MessageMapping`, `@MessageExceptionHandler`, `@SendToUser`), H2 테스트

**Spec:** `docs/design/2026-09-12-stomp-chat-design.md` 3, 4, 5.3~5.5, 6, 7절

## Global Constraints

- 1일차 계획의 Global Constraints 전부 적용 (board 무수정, 2-space, 비밀값 금지, Boot 4 패키지).
- **단계 순서 = 파일 생성 순서 = 의존 방향.** 아직 없는 클래스를 참조하는 파일을 먼저 만들지 않는다. 각 Task 끝에서 `./gradlew compileJava compileTestJava` 또는 `test`가 통과해야 한다. walkthrough 문서가 이 순서를 그대로 따르므로 순서를 바꾸지 않는다.
- 각 Task는 "구현 파일 → 테스트 파일 → 실행 → 커밋" 순. (학습자가 따라가는 순서를 우선해 test-first 대신 impl-first를 택한다.)
- 시각은 전부 JPA Auditing(`@CreatedDate`)으로 채운다. 엔티티 생성자에서 `now()`를 부르지 않는다.
- 파일 삭제(Echo 3종 + `StompAuthIntegrationTest`)는 Task 15에서 사용자 확인 후 수행한다. 확인 전에는 삭제하지 않는다.

---

## 파일 구조 (생성 순서)

| # | 파일 | 책임 |
|---|---|---|
| 1 | `global/exception/ErrorCode.java` (수정) | 방·메시지 코드 4개 추가 |
| 2 | `global/entity/BaseTimeEntity.java`, `global/config/JpaAuditingConfig.java` | 생성·수정 시각 Auditing, 마이크로초 절단 |
| 3 | `room/ChatRoom.java`, `room/ChatRoomRepository.java` | 방 엔티티·조회 |
| 4 | `room/RoomMember.java`, `room/RoomMemberRepository.java` | 멤버십 |
| 5 | `message/MessageType.java`, `message/ChatMessage.java`, `message/ChatMessageRepository.java` | 메시지 + keyset 조회 |
| 6 | `presence/RoomPresenceTracker.java` | 세션·구독 단위 접속자 집계 (메모리) |
| 7 | `room/dto/*`, `message/dto/*` | 요청·응답·이벤트 record |
| 8 | `message/ChatMessagePublisher.java` | `SimpMessagingTemplate` 래핑 |
| 9 | `room/ChatRoomService.java` | 방 유스케이스 + 로비 이벤트 |
| 10 | `room/RoomSecurity.java`, `room/ChatRoomController.java` | REST + 소유자 `@PreAuthorize` |
| 11 | `message/ChatMessageService.java` | 전송·시스템 메시지·이력 |
| 12 | `message/ChatMessageController.java`, `message/MessageHistoryController.java` | STOMP 수신 + 에러 큐, 이력 REST |
| 13 | `presence/RoomPresenceListener.java` | 세션 이벤트 → tracker + ENTER/LEAVE |
| 14 | `auth/SubscriptionAuthorizer.java`, `room/RoomSubscriptionAuthorizer.java`, `auth/StompAuthChannelInterceptor.java` (수정) | SUBSCRIBE 멤버십 검사 |
| 15 | Echo 3종·`StompAuthIntegrationTest` 삭제(확인 후), `message/RoomChatIntegrationTest.java`, `static/index.html` (수정) | 통합 테스트·콘솔 교체 |
| 16 | `docs/lecture/day2_rooms_messages_walkthrough.md`, 설계 문서 소폭 갱신 | 문서 |

---

### Task 1: ErrorCode 확장

**Files:** Modify `src/main/java/com/example/chat/global/exception/ErrorCode.java`

- [ ] `USER_NOT_FOUND` 다음에 추가:

```java
  ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
```

`RESOURCE_NOT_FOUND` 다음에 추가:

```java
  DUPLICATE_ROOM_NAME(HttpStatus.CONFLICT, "이미 존재하는 채팅방 이름입니다."),
  NOT_ROOM_MEMBER(HttpStatus.FORBIDDEN, "채팅방 멤버가 아닙니다. 먼저 입장하세요."),
```

`MALFORMED_REQUEST` 다음에 추가:

```java
  MESSAGE_TOO_LONG(HttpStatus.BAD_REQUEST, "메시지는 1000자 이하여야 합니다."),
```

- [ ] `./gradlew --no-daemon -q compileJava` 통과 → 커밋 `feat: ErrorCode — 방·메시지 코드 4개 추가`

---

### Task 2: BaseTimeEntity + JpaAuditingConfig

**Files:** Create `global/entity/BaseTimeEntity.java`, `global/config/JpaAuditingConfig.java`

```java
package com.example.chat.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 생성·수정 시각을 JPA Auditing이 자동으로 채운다. 엔티티 생성자에서 now()를 부르지 않는다.
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

  @CreatedDate
  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @LastModifiedDate
  @Column(nullable = false)
  private LocalDateTime updatedAt;
}
```

```java
package com.example.chat.global.config;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// Auditing 시각을 DB 정밀도(마이크로초)로 절단한다. JDK의 now()는 나노초까지 주므로
// 절단 없이는 "메모리 엔티티의 createdAt ≠ DB에 저장된 createdAt"가 된다 (board 단계 16의 버그).
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

  @Bean
  public DateTimeProvider auditingDateTimeProvider() {
    return () -> Optional.of(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
  }
}
```

- [ ] `./gradlew --no-daemon -q test` 통과 (기존 43개) → 커밋 `feat: JPA Auditing — BaseTimeEntity + 마이크로초 절단`

---

### Task 3: ChatRoom + ChatRoomRepository

**Files:** Create `room/ChatRoom.java`, `room/ChatRoomRepository.java`, Test `room/ChatRoomRepositoryTest.java`

```java
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
```

```java
package com.example.chat.room;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

  boolean existsByName(String name);

  Page<ChatRoom> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

테스트:

```java
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
```

- [ ] `./gradlew --no-daemon -q test --tests 'com.example.chat.room.*'` 통과 → 커밋 `feat: ChatRoom 엔티티 + 리포지토리`

---

### Task 4: RoomMember + RoomMemberRepository

```java
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
```

```java
package com.example.chat.room;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

  boolean existsByRoomIdAndUserId(Long roomId, Long userId);

  long countByRoomId(Long roomId);

  List<RoomMember> findAllByRoomIdOrderByJoinedAtAsc(Long roomId);

  void deleteByRoomIdAndUserId(Long roomId, Long userId);

  void deleteByRoomId(Long roomId);
}
```

테스트 `room/RoomMemberRepositoryTest.java`:

```java
package com.example.chat.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class RoomMemberRepositoryTest {

  @Autowired ChatRoomRepository chatRoomRepository;
  @Autowired RoomMemberRepository roomMemberRepository;

  private ChatRoom room;

  @BeforeEach
  void setUp() {
    room = chatRoomRepository.save(new ChatRoom("members", null, 1L, "alice"));
  }

  @Test
  void tracksMembershipPerRoom() {
    roomMemberRepository.save(new RoomMember(room, 1L, "alice"));
    roomMemberRepository.save(new RoomMember(room, 3L, "bob"));

    assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.getId(), 1L)).isTrue();
    assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.getId(), 99L)).isFalse();
    assertThat(roomMemberRepository.countByRoomId(room.getId())).isEqualTo(2);
    assertThat(roomMemberRepository.findAllByRoomIdOrderByJoinedAtAsc(room.getId()))
        .extracting(RoomMember::getUsername).containsExactly("alice", "bob");
  }

  @Test
  void rejectsDuplicateMembership() {
    roomMemberRepository.saveAndFlush(new RoomMember(room, 1L, "alice"));

    assertThatThrownBy(() -> roomMemberRepository.saveAndFlush(new RoomMember(room, 1L, "alice")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletesByRoomAndUser() {
    roomMemberRepository.save(new RoomMember(room, 1L, "alice"));

    roomMemberRepository.deleteByRoomIdAndUserId(room.getId(), 1L);

    assertThat(roomMemberRepository.countByRoomId(room.getId())).isZero();
  }
}
```

- [ ] 테스트 통과 → 커밋 `feat: RoomMember 엔티티 + 리포지토리`

---

### Task 5: MessageType, ChatMessage, ChatMessageRepository

```java
package com.example.chat.message;

public enum MessageType {
  TALK, ENTER, LEAVE
}
```

```java
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
```

```java
package com.example.chat.message;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

  // 최신부터 size개 (첫 페이지)
  List<ChatMessage> findByRoomIdOrderByIdDesc(Long roomId, Pageable pageable);

  // before(id)보다 오래된 것부터 size개 (다음 페이지, keyset)
  List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long before, Pageable pageable);

  void deleteByRoomId(Long roomId);
}
```

테스트 `message/ChatMessageRepositoryTest.java`:

```java
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
    List<ChatMessage> page = chatMessageRepository.findByRoomIdOrderByIdDesc(room.getId(), PageRequest.of(0, 2));

    assertThat(page).extracting(ChatMessage::getContent).containsExactly("m5", "m4");
    assertThat(page.get(0).getCreatedAt()).isNotNull();
  }

  @Test
  void keysetPageContinuesBelowCursor() {
    List<ChatMessage> first = chatMessageRepository.findByRoomIdOrderByIdDesc(room.getId(), PageRequest.of(0, 2));
    Long cursor = first.get(1).getId();

    List<ChatMessage> next = chatMessageRepository
        .findByRoomIdAndIdLessThanOrderByIdDesc(room.getId(), cursor, PageRequest.of(0, 2));

    assertThat(next).extracting(ChatMessage::getContent).containsExactly("m3", "m2");
  }
}
```

- [ ] 테스트 통과 → 커밋 `feat: ChatMessage 엔티티 + keyset 조회 리포지토리`

---

### Task 6: RoomPresenceTracker

```java
package com.example.chat.presence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

// "지금 방을 보고 있는가"를 세션·구독 단위로 센다 (메모리, 휘발). 영속 멤버십(RoomMember)과 다르다.
// 같은 사용자가 탭 두 개로 들어오면 세션은 2, 사용자는 1 — ENTER/LEAVE 시스템 메시지는 사용자 단위로 한 번만.
@Component
public class RoomPresenceTracker {

  public record Presence(Long roomId, String username, boolean lastForUser) {
  }

  private record Subscription(Long roomId, String username) {
  }

  // sessionId -> (subscriptionId -> 구독)  : UNSUBSCRIBE/DISCONNECT 때 어느 방이었는지 찾기 위함
  private final Map<String, Map<String, Subscription>> bySession = new HashMap<>();
  // roomId -> (username -> 열려 있는 구독 수)
  private final Map<Long, Map<String, Integer>> byRoom = new HashMap<>();

  // 반환값: 이 사용자가 이 방에 "처음" 나타났는가 (ENTER 메시지 발행 기준)
  public synchronized boolean subscribe(String sessionId, String subscriptionId, Long roomId, String username) {
    bySession.computeIfAbsent(sessionId, k -> new HashMap<>())
        .put(subscriptionId, new Subscription(roomId, username));
    Map<String, Integer> users = byRoom.computeIfAbsent(roomId, k -> new HashMap<>());
    int count = users.merge(username, 1, Integer::sum);
    return count == 1;
  }

  public synchronized Optional<Presence> unsubscribe(String sessionId, String subscriptionId) {
    Map<String, Subscription> subs = bySession.get(sessionId);
    if (subs == null) {
      return Optional.empty();
    }
    Subscription sub = subs.remove(subscriptionId);
    if (subs.isEmpty()) {
      bySession.remove(sessionId);
    }
    return sub == null ? Optional.empty() : Optional.of(release(sub));
  }

  public synchronized List<Presence> disconnect(String sessionId) {
    Map<String, Subscription> subs = bySession.remove(sessionId);
    List<Presence> released = new ArrayList<>();
    if (subs != null) {
      subs.values().forEach(sub -> released.add(release(sub)));
    }
    return released;
  }

  public synchronized int onlineCount(Long roomId) {
    Map<String, Integer> users = byRoom.get(roomId);
    return users == null ? 0 : users.size();
  }

  public synchronized boolean isOnline(Long roomId, String username) {
    Map<String, Integer> users = byRoom.get(roomId);
    return users != null && users.containsKey(username);
  }

  private Presence release(Subscription sub) {
    Map<String, Integer> users = byRoom.get(sub.roomId());
    boolean last = false;
    if (users != null) {
      Integer remaining = users.merge(sub.username(), -1, Integer::sum);
      if (remaining == null || remaining <= 0) {
        users.remove(sub.username());
        last = true;
      }
      if (users.isEmpty()) {
        byRoom.remove(sub.roomId());
      }
    }
    return new Presence(sub.roomId(), sub.username(), last);
  }
}
```

테스트 `presence/RoomPresenceTrackerTest.java`:

```java
package com.example.chat.presence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoomPresenceTrackerTest {

  private final RoomPresenceTracker tracker = new RoomPresenceTracker();

  @Test
  void firstSubscriptionOfUserIsEnter() {
    assertThat(tracker.subscribe("s1", "sub-1", 10L, "alice")).isTrue();
    assertThat(tracker.subscribe("s2", "sub-1", 10L, "alice")).isFalse(); // 두 번째 탭
    assertThat(tracker.onlineCount(10L)).isEqualTo(1);
    assertThat(tracker.isOnline(10L, "alice")).isTrue();
  }

  @Test
  void lastUnsubscribeOfUserIsLeave() {
    tracker.subscribe("s1", "sub-1", 10L, "alice");
    tracker.subscribe("s2", "sub-1", 10L, "alice");

    assertThat(tracker.unsubscribe("s1", "sub-1")).get().extracting("lastForUser").isEqualTo(false);
    assertThat(tracker.unsubscribe("s2", "sub-1")).get().extracting("lastForUser").isEqualTo(true);
    assertThat(tracker.onlineCount(10L)).isZero();
    assertThat(tracker.unsubscribe("s2", "sub-1")).isEmpty();
  }

  @Test
  void disconnectReleasesEveryRoomOfSession() {
    tracker.subscribe("s1", "sub-1", 10L, "alice");
    tracker.subscribe("s1", "sub-2", 20L, "alice");
    tracker.subscribe("s9", "sub-1", 20L, "bob");

    var released = tracker.disconnect("s1");

    assertThat(released).extracting("roomId", "lastForUser").containsExactlyInAnyOrder(
        org.assertj.core.groups.Tuple.tuple(10L, true), org.assertj.core.groups.Tuple.tuple(20L, true));
    assertThat(tracker.onlineCount(20L)).isEqualTo(1);
    assertThat(tracker.disconnect("s1")).isEmpty();
  }
}
```

- [ ] 테스트 통과 → 커밋 `feat: RoomPresenceTracker — 세션·구독 단위 접속자 집계`

---

### Task 7: DTO

**Files:** `room/dto/RoomCreateRequest.java`, `RoomResponse.java`, `MemberResponse.java`; `message/dto/SendMessageRequest.java`, `MessageResponse.java`, `MessagePageResponse.java`, `RoomEventType.java`, `RoomEvent.java`

```java
package com.example.chat.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RoomCreateRequest(
    @NotBlank @Size(max = 50) String name,
    @Size(max = 200) String description
) {
}
```

```java
package com.example.chat.room.dto;

import com.example.chat.room.ChatRoom;
import java.time.LocalDateTime;

public record RoomResponse(
    Long id,
    String name,
    String description,
    String ownerUsername,
    long memberCount,
    int onlineCount,
    LocalDateTime createdAt
) {

  public static RoomResponse of(ChatRoom room, long memberCount, int onlineCount) {
    return new RoomResponse(room.getId(), room.getName(), room.getDescription(),
        room.getOwnerUsername(), memberCount, onlineCount, room.getCreatedAt());
  }
}
```

```java
package com.example.chat.room.dto;

import com.example.chat.room.RoomMember;
import java.time.LocalDateTime;

public record MemberResponse(Long userId, String username, boolean online, LocalDateTime joinedAt) {

  public static MemberResponse of(RoomMember member, boolean online) {
    return new MemberResponse(member.getUserId(), member.getUsername(), online, member.getJoinedAt());
  }
}
```

```java
package com.example.chat.message.dto;

public record SendMessageRequest(String content) {
}
```

```java
package com.example.chat.message.dto;

import com.example.chat.message.ChatMessage;
import com.example.chat.message.MessageType;
import java.time.LocalDateTime;

public record MessageResponse(
    Long id,
    Long roomId,
    MessageType type,
    Long senderUserId,
    String senderUsername,
    String senderNickname,
    String content,
    LocalDateTime createdAt
) {

  public static MessageResponse from(ChatMessage message) {
    return new MessageResponse(message.getId(), message.getRoom().getId(), message.getType(),
        message.getSenderUserId(), message.getSenderUsername(), message.getSenderNickname(),
        message.getContent(), message.getCreatedAt());
  }
}
```

```java
package com.example.chat.message.dto;

import java.util.List;

// messages는 오래된 순. nextBefore는 다음 페이지 요청의 ?before= 값 (가장 오래된 id).
public record MessagePageResponse(List<MessageResponse> messages, boolean hasMore, Long nextBefore) {
}
```

```java
package com.example.chat.message.dto;

public enum RoomEventType {
  ROOM_CREATED, ROOM_DELETED, MEMBER_COUNT
}
```

```java
package com.example.chat.message.dto;

import com.example.chat.room.dto.RoomResponse;

// 로비(/topic/rooms) 구독자에게 보내는 방 목록 변화 알림
public record RoomEvent(RoomEventType type, RoomResponse room) {
}
```

- [ ] `./gradlew --no-daemon -q compileJava` → 커밋 `feat: 방·메시지 DTO`

---

### Task 8: ChatMessagePublisher

```java
package com.example.chat.message;

import com.example.chat.message.dto.MessageResponse;
import com.example.chat.message.dto.RoomEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// 브로커에 쓰는 유일한 곳. 나중에 Redis 백플레인으로 바꿔도 서비스 코드는 손대지 않는다.
@Component
@RequiredArgsConstructor
public class ChatMessagePublisher {

  public static final String LOBBY_TOPIC = "/topic/rooms";

  private final SimpMessagingTemplate messagingTemplate;

  public static String roomTopic(Long roomId) {
    return "/topic/rooms/" + roomId;
  }

  public void publishMessage(MessageResponse message) {
    messagingTemplate.convertAndSend(roomTopic(message.roomId()), message);
  }

  public void publishRoomEvent(RoomEvent event) {
    messagingTemplate.convertAndSend(LOBBY_TOPIC, event);
  }
}
```

테스트 `message/ChatMessagePublisherTest.java`:

```java
package com.example.chat.message;

import static org.mockito.Mockito.verify;

import com.example.chat.message.dto.MessageResponse;
import com.example.chat.message.dto.RoomEvent;
import com.example.chat.message.dto.RoomEventType;
import com.example.chat.room.dto.RoomResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class ChatMessagePublisherTest {

  @Mock SimpMessagingTemplate messagingTemplate;

  @Test
  void publishesMessageToRoomTopic() {
    MessageResponse message = new MessageResponse(1L, 7L, MessageType.TALK, 1L, "alice", "앨리스",
        "hi", LocalDateTime.now());

    new ChatMessagePublisher(messagingTemplate).publishMessage(message);

    verify(messagingTemplate).convertAndSend("/topic/rooms/7", message);
  }

  @Test
  void publishesRoomEventToLobby() {
    RoomEvent event = new RoomEvent(RoomEventType.ROOM_CREATED,
        new RoomResponse(7L, "general", null, "alice", 1, 0, LocalDateTime.now()));

    new ChatMessagePublisher(messagingTemplate).publishRoomEvent(event);

    verify(messagingTemplate).convertAndSend("/topic/rooms", event);
  }
}
```

- [ ] 테스트 통과 → 커밋 `feat: ChatMessagePublisher — 브로커 접근 단일 지점`

---

### Task 9: ChatRoomService

```java
package com.example.chat.room;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.global.exception.DuplicateException;
import com.example.chat.global.exception.ErrorCode;
import com.example.chat.global.exception.ForbiddenException;
import com.example.chat.global.exception.NotFoundException;
import com.example.chat.message.ChatMessagePublisher;
import com.example.chat.message.ChatMessageRepository;
import com.example.chat.message.dto.RoomEvent;
import com.example.chat.message.dto.RoomEventType;
import com.example.chat.presence.RoomPresenceTracker;
import com.example.chat.room.dto.MemberResponse;
import com.example.chat.room.dto.RoomCreateRequest;
import com.example.chat.room.dto.RoomResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

  private final ChatRoomRepository chatRoomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final RoomPresenceTracker presenceTracker;
  private final ChatMessagePublisher publisher;

  // 만든 사람은 자동 입장. 로비에 ROOM_CREATED 알림.
  @Transactional
  public RoomResponse create(RoomCreateRequest request, ChatPrincipal principal) {
    if (chatRoomRepository.existsByName(request.name())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_ROOM_NAME);
    }
    ChatRoom room = chatRoomRepository.save(new ChatRoom(
        request.name(), request.description(), principal.userId(), principal.username()));
    roomMemberRepository.save(new RoomMember(room, principal.userId(), principal.username()));
    RoomResponse response = toResponse(room);
    publisher.publishRoomEvent(new RoomEvent(RoomEventType.ROOM_CREATED, response));
    return response;
  }

  @Transactional(readOnly = true)
  public Page<RoomResponse> list(Pageable pageable) {
    return chatRoomRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toResponse);
  }

  @Transactional(readOnly = true)
  public RoomResponse get(Long roomId) {
    return toResponse(findRoom(roomId));
  }

  // 멱등: 이미 멤버면 그대로 200
  @Transactional
  public RoomResponse join(Long roomId, ChatPrincipal principal) {
    ChatRoom room = findRoom(roomId);
    if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, principal.userId())) {
      roomMemberRepository.save(new RoomMember(room, principal.userId(), principal.username()));
      publisher.publishRoomEvent(new RoomEvent(RoomEventType.MEMBER_COUNT, toResponse(room)));
    }
    return toResponse(room);
  }

  @Transactional
  public void leave(Long roomId, ChatPrincipal principal) {
    ChatRoom room = findRoom(roomId);
    if (roomMemberRepository.existsByRoomIdAndUserId(roomId, principal.userId())) {
      roomMemberRepository.deleteByRoomIdAndUserId(roomId, principal.userId());
      publisher.publishRoomEvent(new RoomEvent(RoomEventType.MEMBER_COUNT, toResponse(room)));
    }
  }

  // 소유자 검사는 컨트롤러의 @PreAuthorize(RoomSecurity)가 한다. 자식(메시지·멤버)을 먼저 지운다 (cascade 없음).
  @Transactional
  public void delete(Long roomId) {
    ChatRoom room = findRoom(roomId);
    RoomResponse snapshot = toResponse(room);
    chatMessageRepository.deleteByRoomId(roomId);
    roomMemberRepository.deleteByRoomId(roomId);
    chatRoomRepository.delete(room);
    publisher.publishRoomEvent(new RoomEvent(RoomEventType.ROOM_DELETED, snapshot));
  }

  @Transactional(readOnly = true)
  public List<MemberResponse> members(Long roomId, ChatPrincipal principal) {
    findRoom(roomId);
    requireMember(roomId, principal.userId());
    return roomMemberRepository.findAllByRoomIdOrderByJoinedAtAsc(roomId).stream()
        .map(member -> MemberResponse.of(member, presenceTracker.isOnline(roomId, member.getUsername())))
        .toList();
  }

  @Transactional(readOnly = true)
  public boolean isMember(Long roomId, Long userId) {
    return roomMemberRepository.existsByRoomIdAndUserId(roomId, userId);
  }

  private void requireMember(Long roomId, Long userId) {
    if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
      throw new ForbiddenException(ErrorCode.NOT_ROOM_MEMBER);
    }
  }

  private ChatRoom findRoom(Long roomId) {
    return chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.ROOM_NOT_FOUND));
  }

  private RoomResponse toResponse(ChatRoom room) {
    return RoomResponse.of(room,
        roomMemberRepository.countByRoomId(room.getId()),
        presenceTracker.onlineCount(room.getId()));
  }
}
```

테스트 `room/ChatRoomServiceTest.java` (`@MockitoBean`으로 publisher만 대체해 이벤트 발행 검증):

```java
package com.example.chat.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.global.exception.DuplicateException;
import com.example.chat.global.exception.ForbiddenException;
import com.example.chat.global.exception.NotFoundException;
import com.example.chat.message.ChatMessagePublisher;
import com.example.chat.message.dto.RoomEventType;
import com.example.chat.room.dto.RoomCreateRequest;
import com.example.chat.room.dto.RoomResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ChatRoomServiceTest {

  @Autowired ChatRoomService chatRoomService;
  @Autowired RoomMemberRepository roomMemberRepository;
  @MockitoBean ChatMessagePublisher publisher;

  private final ChatPrincipal alice = new ChatPrincipal(1L, "alice", "앨리스", "jti-a", Instant.MAX);
  private final ChatPrincipal bob = new ChatPrincipal(3L, "bob", "밥", "jti-b", Instant.MAX);

  @Test
  void createAddsOwnerAsMemberAndPublishesEvent() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("general", "잡담"), alice);

    assertThat(room.ownerUsername()).isEqualTo("alice");
    assertThat(room.memberCount()).isEqualTo(1);
    assertThat(roomMemberRepository.existsByRoomIdAndUserId(room.id(), 1L)).isTrue();
    verify(publisher).publishRoomEvent(argThat(e -> e.type() == RoomEventType.ROOM_CREATED));
  }

  @Test
  void createRejectsDuplicateName() {
    chatRoomService.create(new RoomCreateRequest("dup", null), alice);

    assertThatThrownBy(() -> chatRoomService.create(new RoomCreateRequest("dup", null), bob))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void joinIsIdempotentAndPublishesOnce() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("join", null), alice);

    chatRoomService.join(room.id(), bob);
    RoomResponse again = chatRoomService.join(room.id(), bob);

    assertThat(again.memberCount()).isEqualTo(2);
    verify(publisher, times(1)).publishRoomEvent(argThat(e -> e.type() == RoomEventType.MEMBER_COUNT));
  }

  @Test
  void leaveRemovesMembership() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("leave", null), alice);
    chatRoomService.join(room.id(), bob);

    chatRoomService.leave(room.id(), bob);
    chatRoomService.leave(room.id(), bob); // 멱등

    assertThat(chatRoomService.get(room.id()).memberCount()).isEqualTo(1);
  }

  @Test
  void membersRequiresMembership() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("members", null), alice);

    assertThat(chatRoomService.members(room.id(), alice)).extracting("username").containsExactly("alice");
    assertThatThrownBy(() -> chatRoomService.members(room.id(), bob)).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void deleteRemovesRoomAndPublishes() {
    RoomResponse room = chatRoomService.create(new RoomCreateRequest("delete", null), alice);

    chatRoomService.delete(room.id());

    assertThatThrownBy(() -> chatRoomService.get(room.id())).isInstanceOf(NotFoundException.class);
    assertThat(roomMemberRepository.countByRoomId(room.id())).isZero();
    verify(publisher).publishRoomEvent(argThat(e -> e.type() == RoomEventType.ROOM_DELETED));
  }

  @Test
  void listIsNewestFirst() {
    chatRoomService.create(new RoomCreateRequest("older", null), alice);
    chatRoomService.create(new RoomCreateRequest("newer", null), alice);

    assertThat(chatRoomService.list(PageRequest.of(0, 10)).getContent())
        .extracting(RoomResponse::name).containsSubsequence("newer", "older");
  }
}
```

- [ ] 테스트 통과 → 커밋 `feat: ChatRoomService — 생성·목록·입장·퇴장·삭제·멤버 + 로비 이벤트`

---

### Task 10: RoomSecurity + ChatRoomController

`schema-board.sql`에 bob 시드 추가 (MERGE users에 `(3, 'bob', 'bob@example.com', 'x', 'USER', 'LOCAL', NOW(), NOW())`, profiles에 `(2, 3, '밥', NOW(), NOW())`).

```java
package com.example.chat.room;

import com.example.chat.auth.ChatPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// @PreAuthorize("@roomSecurity.isOwner(#id, principal)") 에서 SpEL로 호출되는 빈.
// 방이 없으면 true를 돌려 서비스가 404를 내게 한다 (403보다 정확한 응답).
@Component("roomSecurity")
@RequiredArgsConstructor
public class RoomSecurity {

  private final ChatRoomRepository chatRoomRepository;

  public boolean isOwner(Long roomId, ChatPrincipal principal) {
    return chatRoomRepository.findById(roomId)
        .map(room -> room.isOwnedBy(principal.userId()))
        .orElse(true);
  }
}
```

```java
package com.example.chat.room;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.room.dto.MemberResponse;
import com.example.chat.room.dto.RoomCreateRequest;
import com.example.chat.room.dto.RoomResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

  private final ChatRoomService chatRoomService;

  @GetMapping
  public PagedModel<RoomResponse> list(@PageableDefault(size = 20) Pageable pageable) {
    return new PagedModel<>(chatRoomService.list(pageable));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoomResponse create(
      @Valid @RequestBody RoomCreateRequest request,
      @AuthenticationPrincipal ChatPrincipal principal) {
    return chatRoomService.create(request, principal);
  }

  @GetMapping("/{id}")
  public RoomResponse get(@PathVariable Long id) {
    return chatRoomService.get(id);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@roomSecurity.isOwner(#id, principal)")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    chatRoomService.delete(id);
  }

  @PostMapping("/{id}/join")
  public RoomResponse join(@PathVariable Long id, @AuthenticationPrincipal ChatPrincipal principal) {
    return chatRoomService.join(id, principal);
  }

  @DeleteMapping("/{id}/leave")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void leave(@PathVariable Long id, @AuthenticationPrincipal ChatPrincipal principal) {
    chatRoomService.leave(id, principal);
  }

  @GetMapping("/{id}/members")
  public List<MemberResponse> members(
      @PathVariable Long id, @AuthenticationPrincipal ChatPrincipal principal) {
    return chatRoomService.members(id, principal);
  }
}
```

테스트 `room/ChatRoomControllerTest.java` (MockMvc, `TestJwtFactory.token("alice")`/`"bob"`):
- POST 생성 201 + `ownerUsername`=alice, `memberCount`=1
- 같은 이름 재생성 409 `DUPLICATE_ROOM_NAME`
- 빈 이름 400 `INVALID_INPUT` + `errors[0].field`=name
- GET 목록 200 `content[*].name` 포함, `page.size`=20
- bob이 DELETE → 403 `ACCESS_DENIED`; alice가 DELETE → 204; 이후 GET → 404 `ROOM_NOT_FOUND`
- bob이 `/members` → 403 `NOT_ROOM_MEMBER`; bob이 join 200 → `/members` 200 두 명

- [ ] 테스트 통과 → 커밋 `feat: 방 REST — ChatRoomController + RoomSecurity 소유자 인가`

---

### Task 11: ChatMessageService

```java
package com.example.chat.message;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.global.exception.BusinessException;
import com.example.chat.global.exception.ErrorCode;
import com.example.chat.global.exception.ForbiddenException;
import com.example.chat.global.exception.NotFoundException;
import com.example.chat.message.dto.MessagePageResponse;
import com.example.chat.message.dto.MessageResponse;
import com.example.chat.room.ChatRoom;
import com.example.chat.room.ChatRoomRepository;
import com.example.chat.room.RoomMemberRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageService {

  public static final int MAX_CONTENT_LENGTH = 1000;
  public static final int MAX_PAGE_SIZE = 100;

  private final ChatRoomRepository chatRoomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatMessagePublisher publisher;

  // 저장 후 브로드캐스트. 검증 순서: 내용 → 방 존재 → 멤버십 (싼 것부터).
  @Transactional
  public MessageResponse send(Long roomId, ChatPrincipal sender, String content) {
    String text = content == null ? "" : content.strip();
    if (text.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
    if (text.length() > MAX_CONTENT_LENGTH) {
      throw new BusinessException(ErrorCode.MESSAGE_TOO_LONG);
    }
    ChatRoom room = findRoom(roomId);
    requireMember(roomId, sender.userId());
    return saveAndPublish(new ChatMessage(room, MessageType.TALK, sender.userId(),
        sender.username(), sender.nickname(), text));
  }

  // ENTER/LEAVE — presence 리스너가 호출. 멤버십 검사는 구독 시점(인터셉터)에 이미 끝났다.
  @Transactional
  public MessageResponse system(Long roomId, MessageType type, ChatPrincipal user) {
    ChatRoom room = findRoom(roomId);
    String text = user.nickname() + (type == MessageType.ENTER ? "님이 입장했습니다." : "님이 퇴장했습니다.");
    return saveAndPublish(new ChatMessage(room, type, user.userId(),
        user.username(), user.nickname(), text));
  }

  // keyset: size+1개를 읽어 hasMore 판정, 응답은 오래된 순으로 뒤집는다.
  @Transactional(readOnly = true)
  public MessagePageResponse history(Long roomId, ChatPrincipal reader, Long before, int size) {
    findRoom(roomId);
    requireMember(roomId, reader.userId());
    int limit = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    Pageable page = PageRequest.of(0, limit + 1);
    List<ChatMessage> rows = before == null
        ? chatMessageRepository.findByRoomIdOrderByIdDesc(roomId, page)
        : chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, before, page);
    boolean hasMore = rows.size() > limit;
    List<ChatMessage> window = hasMore ? rows.subList(0, limit) : rows;
    List<MessageResponse> ascending = new ArrayList<>(window.stream().map(MessageResponse::from).toList());
    Collections.reverse(ascending);
    Long nextBefore = ascending.isEmpty() ? null : ascending.get(0).id();
    return new MessagePageResponse(ascending, hasMore, nextBefore);
  }

  private MessageResponse saveAndPublish(ChatMessage message) {
    MessageResponse response = MessageResponse.from(chatMessageRepository.save(message));
    publisher.publishMessage(response);
    return response;
  }

  private void requireMember(Long roomId, Long userId) {
    if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
      throw new ForbiddenException(ErrorCode.NOT_ROOM_MEMBER);
    }
  }

  private ChatRoom findRoom(Long roomId) {
    return chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.ROOM_NOT_FOUND));
  }
}
```

테스트 `message/ChatMessageServiceTest.java` (`@SpringBootTest @Transactional`, `@MockitoBean ChatMessagePublisher`):
- send 저장 + `publishMessage` 호출, `senderNickname`=앨리스, `type`=TALK
- 빈 내용 → `BusinessException` code INVALID_INPUT; 1001자 → MESSAGE_TOO_LONG
- 비멤버 bob → ForbiddenException; 없는 방 → NotFoundException
- system ENTER → content "앨리스님이 입장했습니다."
- history: 7개 저장 후 size=3 → 최신 3개 오래된 순, hasMore=true, nextBefore=그 중 가장 오래된 id; before=nextBefore로 size=3 → 다음 3개; 마지막 페이지 hasMore=false

- [ ] 테스트 통과 → 커밋 `feat: ChatMessageService — 전송·시스템 메시지·keyset 이력`

---

### Task 12: ChatMessageController(STOMP) + MessageHistoryController(REST)

```java
package com.example.chat.message;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.auth.StompAuthException;
import com.example.chat.global.exception.BusinessException;
import com.example.chat.global.exception.ErrorCode;
import com.example.chat.global.exception.ErrorResponse;
import com.example.chat.message.dto.SendMessageRequest;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

// SEND /app/rooms/{roomId}/messages 를 받는다. 반환값이 없다 — 브로드캐스트는 서비스가 publisher로 한다.
// 검증·권한 실패는 연결을 끊지 않고 보낸 사람의 /user/queue/errors 로만 알린다 (인증 실패와 다른 처리).
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageController {

  private final ChatMessageService chatMessageService;

  @MessageMapping("/rooms/{roomId}/messages")
  public void send(
      @DestinationVariable Long roomId,
      @Payload SendMessageRequest request,
      Principal principal) {
    ChatPrincipal sender = ChatPrincipal.from(principal)
        .orElseThrow(() -> new StompAuthException(ErrorCode.LOGIN_REQUIRED));
    chatMessageService.send(roomId, sender, request.content());
  }

  @MessageExceptionHandler(BusinessException.class)
  @SendToUser("/queue/errors")
  public ErrorResponse handleBusiness(BusinessException e) {
    log.warn("STOMP business error: {}", e.getErrorCode().name());
    return ErrorResponse.of(e.getErrorCode());
  }

  @MessageExceptionHandler(Exception.class)
  @SendToUser("/queue/errors")
  public ErrorResponse handleUnexpected(Exception e) {
    log.error("STOMP unexpected error", e);
    return ErrorResponse.of(ErrorCode.INTERNAL_ERROR);
  }
}
```

```java
package com.example.chat.message;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.message.dto.MessagePageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class MessageHistoryController {

  private final ChatMessageService chatMessageService;

  @GetMapping
  public MessagePageResponse history(
      @PathVariable Long roomId,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "50") int size,
      @AuthenticationPrincipal ChatPrincipal principal) {
    return chatMessageService.history(roomId, principal, before, size);
  }
}
```

테스트 `message/MessageHistoryControllerTest.java` (MockMvc): 방 생성·메시지 3개(서비스 직접 호출, `@MockitoBean` publisher) 후 alice GET → 200 `messages` 3개 오래된 순, `hasMore` false; `?size=2` → hasMore true; bob GET → 403 `NOT_ROOM_MEMBER`; 없는 방 → 404. STOMP 컨트롤러는 Task 15 통합 테스트에서 검증.

- [ ] 테스트 통과 → 커밋 `feat: STOMP 메시지 수신 컨트롤러 + /user/queue/errors + 이력 REST`

---

### Task 13: RoomPresenceListener

```java
package com.example.chat.presence;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.message.ChatMessageService;
import com.example.chat.message.MessageType;
import java.security.Principal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

// Spring이 STOMP 프레임 처리 후 발행하는 세션 이벤트를 듣는다. 인터셉터에서 거부된 SUBSCRIBE는
// 이벤트가 발행되지 않으므로(preSend 예외 → send 실패) 여기서는 멤버십을 다시 검사하지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomPresenceListener {

  private static final Pattern ROOM_TOPIC = Pattern.compile("^/topic/rooms/(\\d+)$");

  private final RoomPresenceTracker tracker;
  private final ChatMessageService chatMessageService;

  @EventListener
  public void onSubscribe(SessionSubscribeEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    Optional<Long> roomId = roomIdOf(accessor.getDestination());
    Optional<ChatPrincipal> user = ChatPrincipal.from(event.getUser());
    if (roomId.isEmpty() || user.isEmpty()) {
      return;
    }
    boolean first = tracker.subscribe(
        accessor.getSessionId(), accessor.getSubscriptionId(), roomId.get(), user.get().username());
    if (first) {
      chatMessageService.system(roomId.get(), MessageType.ENTER, user.get());
    }
  }

  @EventListener
  public void onUnsubscribe(SessionUnsubscribeEvent event) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
    tracker.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId())
        .filter(RoomPresenceTracker.Presence::lastForUser)
        .ifPresent(presence -> leave(presence, event.getUser()));
  }

  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    tracker.disconnect(event.getSessionId()).stream()
        .filter(RoomPresenceTracker.Presence::lastForUser)
        .forEach(presence -> leave(presence, event.getUser()));
  }

  private void leave(RoomPresenceTracker.Presence presence, Principal user) {
    ChatPrincipal.from(user)
        .ifPresent(p -> chatMessageService.system(presence.roomId(), MessageType.LEAVE, p));
  }

  static Optional<Long> roomIdOf(String destination) {
    if (destination == null) {
      return Optional.empty();
    }
    Matcher m = ROOM_TOPIC.matcher(destination);
    return m.matches() ? Optional.of(Long.valueOf(m.group(1))) : Optional.empty();
  }
}
```

테스트 `presence/RoomPresenceListenerTest.java` (Mockito, 실제 `RoomPresenceTracker`, mock `ChatMessageService`): SUBSCRIBE `/topic/rooms/7` 이벤트 → `system(7, ENTER, alice)` 1회; 같은 사용자 두 번째 세션 → 호출 없음; `/topic/rooms`(로비) → 호출 없음; UNSUBSCRIBE 마지막 → LEAVE; DISCONNECT → LEAVE. 이벤트 생성:

```java
private static Message<byte[]> frame(StompCommand command, String sessionId, String subId, String destination) {
  StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
  accessor.setSessionId(sessionId);
  accessor.setSubscriptionId(subId);
  if (destination != null) {
    accessor.setDestination(destination);
  }
  return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
}
// new SessionSubscribeEvent(this, frame(SUBSCRIBE, "s1", "sub-1", "/topic/rooms/7"), alice.toAuthentication())
// new SessionUnsubscribeEvent(this, frame(UNSUBSCRIBE, "s1", "sub-1", null), auth)
// new SessionDisconnectEvent(this, frame(DISCONNECT, "s1", null, null), "s1", CloseStatus.NORMAL, auth)
```

- [ ] 테스트 통과 → 커밋 `feat: RoomPresenceListener — 세션 이벤트로 입장·퇴장 시스템 메시지`

---

### Task 14: SUBSCRIBE 멤버십 검사 (SubscriptionAuthorizer)

```java
package com.example.chat.auth;

// SUBSCRIBE destination을 이 사용자가 구독해도 되는가. 구현은 room 패키지(auth → room 의존을 피한다).
public interface SubscriptionAuthorizer {

  boolean canSubscribe(String destination, ChatPrincipal principal);
}
```

```java
package com.example.chat.room;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.auth.SubscriptionAuthorizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// /topic/rooms/{id} 는 멤버만. 그 외(/topic/rooms 로비, /user/queue/errors)는 인증만 되면 허용.
@Component
@RequiredArgsConstructor
public class RoomSubscriptionAuthorizer implements SubscriptionAuthorizer {

  private static final Pattern ROOM_TOPIC = Pattern.compile("^/topic/rooms/(\\d+)$");

  private final ChatRoomService chatRoomService;

  @Override
  public boolean canSubscribe(String destination, ChatPrincipal principal) {
    if (destination == null) {
      return true;
    }
    Matcher m = ROOM_TOPIC.matcher(destination);
    if (!m.matches()) {
      return true;
    }
    return chatRoomService.isMember(Long.valueOf(m.group(1)), principal.userId());
  }
}
```

`StompAuthChannelInterceptor` 수정: 필드 `private final SubscriptionAuthorizer subscriptionAuthorizer;` 추가, `preSend`의 switch를

```java
    switch (accessor.getCommand()) {
      case CONNECT -> authenticateConnect(accessor);
      case SEND -> requireLivePrincipal(accessor);
      case SUBSCRIBE -> authorizeSubscribe(accessor);
      default -> {
      }
    }
```

로 바꾸고, `requireLivePrincipal`이 `ChatPrincipal`을 반환하게 한 뒤 추가:

```java
  private void authorizeSubscribe(StompHeaderAccessor accessor) {
    ChatPrincipal principal = requireLivePrincipal(accessor);
    if (!subscriptionAuthorizer.canSubscribe(accessor.getDestination(), principal)) {
      throw new StompAuthException(ErrorCode.NOT_ROOM_MEMBER);
    }
  }
```

`StompAuthChannelInterceptorTest`에 `@Mock SubscriptionAuthorizer subscriptionAuthorizer;` 추가. 기존 `subscribeWithDeniedJtiIsRejected`는 denylist가 먼저라 stub 불필요. 추가 테스트: `subscribeRoomTopicAsMemberPasses`(authorizer true), `subscribeRoomTopicAsNonMemberIsRejected`(authorizer false → NOT_ROOM_MEMBER). `RoomSubscriptionAuthorizerTest`(mock ChatRoomService): 로비 true, 방 멤버 true, 비멤버 false, 다른 destination true.

- [ ] 테스트 통과 → 커밋 `feat: SUBSCRIBE 멤버십 검사 — SubscriptionAuthorizer + 인터셉터 연결`

---

### Task 15: Echo 교체 — 통합 테스트·콘솔

**사용자 확인 후** 삭제: `message/EchoController.java`, `message/dto/EchoRequest.java`, `message/dto/EchoResponse.java`, `src/test/.../message/StompAuthIntegrationTest.java`.

새 통합 테스트 `message/RoomChatIntegrationTest.java` (`RANDOM_PORT`, 두 클라이언트, `ChatRoomService`로 방 준비, 1일차 ErrorCapture 재사용):
1. 인증 거부 3종 (무토큰 LOGIN_REQUIRED, 만료 TOKEN_EXPIRED, denylist LOGIN_REQUIRED) — 1일차와 동일
2. alice가 방 생성·구독, bob이 join 후 구독 → alice가 bob의 ENTER `MessageResponse`(type ENTER, senderUsername bob) 수신
3. bob SEND `{content:"hi"}` → alice가 TALK 수신, DB에도 저장(`ChatMessageRepository` count)
4. bob이 미가입 방 SUBSCRIBE → ERROR `code`=NOT_ROOM_MEMBER
5. alice SEND 빈 내용 → `/user/queue/errors` 에 `ErrorResponse` code INVALID_INPUT, 연결 유지
6. bob DISCONNECT → alice가 LEAVE 수신

`static/index.html` 교체: 토큰 + 방 이름/ID 입력, CONNECT 시 `/topic/rooms`·`/user/queue/errors` 자동 구독, CREATE ROOM(POST)·JOIN(POST)·LIST(GET) 버튼(fetch + Bearer), SUBSCRIBE `/topic/rooms/{id}`, SEND `/app/rooms/{id}/messages`, HISTORY(GET) 버튼. `StaticConsoleTest`는 그대로.

- [ ] `./gradlew --no-daemon -q test` 전체 통과 → 커밋 `feat: echo를 방 채팅으로 교체 — 통합 테스트 + 콘솔`

---

### Task 16: 문서

- 설계 문서 3.3 `sender_user_id` 설명을 "시스템 메시지도 대상 사용자 id 저장"으로, 2.3 auth 패키지에 `SubscriptionAuthorizer` 추가, 5.4 SUBSCRIBE 행 갱신.
- `docs/lecture/day2_rooms_messages_walkthrough.md`: Task 1~15 순서 그대로. 각 단계마다 (1) 왜 지금 이 파일인가, (2) 전체 코드, (3) `| 클래스 | 출처 | 역할 |` 표, (4) 확인 명령. 출처 값: `Spring Framework`, `Spring Security`, `Spring Data JPA`, `Spring Messaging`, `Spring WebSocket`, `Jakarta Persistence`, `Jakarta Validation`, `Lombok`, `JUnit/AssertJ/Mockito`, `1일차 (chat)`, `N단계 (chat)`.
- 커밋 `docs: 2일차 방 도메인 walkthrough + 설계 문서 갱신 (코드 무변경)`

---

## Self-Review

- Spec 커버리지: 3(스키마 3표), 4(엔티티·관계 결정), 5.2(SEND·SUBSCRIBE·`/user/queue/errors`·`RoomEvent`), 5.3 DTO, 5.4 SUBSCRIBE 멤버십, 5.5 presence, 6.2 REST 전부(`/me` 제외 8개), 6.4 인가, 7.2 코드 4개, 7.3 `@MessageExceptionHandler` 커버. heartbeat·프론트·배포는 3·4일차.
- 타입 일관성: `RoomResponse(Long id, String name, String description, String ownerUsername, long memberCount, int onlineCount, LocalDateTime createdAt)`, `MessageResponse(Long id, Long roomId, MessageType type, Long senderUserId, String senderUsername, String senderNickname, String content, LocalDateTime createdAt)`, `RoomPresenceTracker.Presence(Long roomId, String username, boolean lastForUser)`, `ChatRoomService.isMember(Long, Long)`가 Task 9~15에서 동일.
