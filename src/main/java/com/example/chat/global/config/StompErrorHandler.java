package com.example.chat.global.config;

import com.example.chat.auth.StompAuthException;
import com.example.chat.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

// @RestControllerAdvice는 STOMP 경로를 못 본다. 인터셉터에서 던진 예외를 ERROR 프레임(code 헤더)으로 바꾼다.
// 예상 외 예외는 INTERNAL_ERROR로 숨기고 서버 로그에만 남긴다 (REST의 GlobalExceptionHandler와 같은 원칙).
@Slf4j
@Component
public class StompErrorHandler extends StompSubProtocolErrorHandler {

  @Override
  public Message<byte[]> handleClientMessageProcessingError(
      Message<byte[]> clientMessage, Throwable ex) {
    ErrorCode errorCode = findErrorCode(ex);
    if (errorCode == ErrorCode.INTERNAL_ERROR) {
      log.error("STOMP 처리 중 예상치 못한 오류", ex);
    } else {
      log.warn("STOMP 거부: code={}", errorCode.name());
    }

    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
    accessor.setMessage(errorCode.getMessage());
    accessor.setNativeHeader("code", errorCode.name());
    accessor.setContentType(MimeTypeUtils.TEXT_PLAIN);
    accessor.setLeaveMutable(true);

    StompHeaderAccessor clientAccessor = clientMessage == null ? null
        : MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
    byte[] payload = errorCode.getMessage().getBytes(StandardCharsets.UTF_8);
    return handleInternal(accessor, payload, ex, clientAccessor);
  }

  private static ErrorCode findErrorCode(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof StompAuthException authException) {
        return authException.getErrorCode();
      }
    }
    return ErrorCode.INTERNAL_ERROR;
  }
}
