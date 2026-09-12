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
