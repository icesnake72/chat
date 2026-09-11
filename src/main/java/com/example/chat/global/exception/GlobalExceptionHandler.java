package com.example.chat.global.exception;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// REST 경로의 예외를 ErrorResponse로 바꾸는 한 곳. STOMP 경로는 여기를 거치지 않는다 (StompErrorHandler 참고).
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    log.warn("BusinessException: code={}, message={}", errorCode.name(), e.getMessage());
    return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
    List<ErrorResponse.FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
        .map(error -> new ErrorResponse.FieldErrorDetail(error.getField(), error.getDefaultMessage()))
        .toList();
    log.warn("Validation failed: {}", errors);
    return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
        .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, errors));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
    log.warn("Malformed request: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.getStatus())
        .body(ErrorResponse.of(ErrorCode.MALFORMED_REQUEST));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
    log.warn("Access denied: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.ACCESS_DENIED.getStatus())
        .body(ErrorResponse.of(ErrorCode.ACCESS_DENIED));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
    return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
        .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    log.error("Unexpected exception", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
        .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
  }
}
