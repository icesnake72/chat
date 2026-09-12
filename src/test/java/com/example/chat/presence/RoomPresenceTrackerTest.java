package com.example.chat.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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

    assertThat(released).extracting("roomId", "lastForUser")
        .containsExactlyInAnyOrder(tuple(10L, true), tuple(20L, true));
    assertThat(tracker.onlineCount(20L)).isEqualTo(1);
    assertThat(tracker.disconnect("s1")).isEmpty();
  }
}
