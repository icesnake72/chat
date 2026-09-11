package com.example.chat.global.exception;

public class NotFoundException extends BusinessException {

  public NotFoundException(ErrorCode errorCode) {
    super(errorCode);
  }
}
