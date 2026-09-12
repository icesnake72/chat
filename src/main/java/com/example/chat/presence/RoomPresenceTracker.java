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
  public synchronized boolean subscribe(
      String sessionId, String subscriptionId, Long roomId, String username) {
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
