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
    String text = user.nickname()
        + (type == MessageType.ENTER ? "님이 입장했습니다." : "님이 퇴장했습니다.");
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
    List<MessageResponse> ascending =
        new ArrayList<>(window.stream().map(MessageResponse::from).toList());
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
