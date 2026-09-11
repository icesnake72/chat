package com.example.chat.auth;

import com.example.chat.global.exception.ErrorCode;
import lombok.Getter;
import org.springframework.messaging.MessagingException;

// 인터셉터에서 던지면 StompSubProtocolHandler가 잡아 ERROR 프레임으로 바꾼다 (StompErrorHandler가 code 헤더를 붙임).
@Getter
public class StompAuthException extends MessagingException {

  private final ErrorCode errorCode;

  public StompAuthException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
