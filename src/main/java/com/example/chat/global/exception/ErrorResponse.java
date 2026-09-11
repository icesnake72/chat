package com.example.chat.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String code,
    String message,
    LocalDateTime timestamp,
    List<FieldErrorDetail> errors
) {

  public record FieldErrorDetail(String field, String reason) {
  }

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now(), null);
  }

  public static ErrorResponse of(ErrorCode errorCode, List<FieldErrorDetail> errors) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now(), errors);
  }
}
