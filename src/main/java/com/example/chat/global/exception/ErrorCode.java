package com.example.chat.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// HTTP 상태의 단일 권위. 하위 예외 클래스는 라벨일 뿐 상태는 여기서만 정한다 (board와 동일).
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "access token이 만료되었습니다. 재발급 후 다시 연결하세요."),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

  DUPLICATE_ROOM_NAME(HttpStatus.CONFLICT, "이미 존재하는 채팅방 이름입니다."),
  NOT_ROOM_MEMBER(HttpStatus.FORBIDDEN, "채팅방 멤버가 아닙니다. 먼저 입장하세요."),

  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문(JSON)을 읽을 수 없습니다."),
  MESSAGE_TOO_LONG(HttpStatus.BAD_REQUEST, "메시지는 1000자 이하여야 합니다."),

  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String message;
}
