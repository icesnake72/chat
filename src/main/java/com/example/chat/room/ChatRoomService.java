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
        .map(member -> MemberResponse.of(
            member, presenceTracker.isOnline(roomId, member.getUsername())))
        .toList();
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
