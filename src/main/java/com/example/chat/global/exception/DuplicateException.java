package com.example.chat.global.exception;

public class DuplicateException extends BusinessException {

  public DuplicateException(ErrorCode errorCode) {
    super(errorCode);
  }
}
