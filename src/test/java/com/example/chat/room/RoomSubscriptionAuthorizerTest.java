package com.example.chat.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.example.chat.auth.ChatPrincipal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomSubscriptionAuthorizerTest {

  @Mock RoomMemberRepository roomMemberRepository;
  @InjectMocks RoomSubscriptionAuthorizer authorizer;

  private final ChatPrincipal alice = new ChatPrincipal(1L, "alice", "앨리스", "jti", Instant.MAX);

  @Test
  void roomTopicRequiresMembership() {
    given(roomMemberRepository.existsByRoomIdAndUserId(7L, 1L)).willReturn(true);
    given(roomMemberRepository.existsByRoomIdAndUserId(8L, 1L)).willReturn(false);

    assertThat(authorizer.canSubscribe("/topic/rooms/7", alice)).isTrue();
    assertThat(authorizer.canSubscribe("/topic/rooms/8", alice)).isFalse();
  }

  @Test
  void otherDestinationsAreAllowed() {
    assertThat(authorizer.canSubscribe("/topic/rooms", alice)).isTrue();
    assertThat(authorizer.canSubscribe("/user/queue/errors", alice)).isTrue();
    assertThat(authorizer.canSubscribe(null, alice)).isTrue();
  }
}
