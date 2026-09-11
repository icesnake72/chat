package com.example.chat.global.exception;

public class UnauthorizedException extends BusinessException {

  public UnauthorizedException(ErrorCode errorCode) {
    super(errorCode);
  }
}
