package com.example.chat.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void businessExceptionUsesErrorCodeStatusAndBody() {
    ResponseEntity<ErrorResponse> response =
        handler.handleBusinessException(new UnauthorizedException(ErrorCode.LOGIN_REQUIRED));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().code()).isEqualTo("LOGIN_REQUIRED");
    assertThat(response.getBody().message()).isEqualTo(ErrorCode.LOGIN_REQUIRED.getMessage());
    assertThat(response.getBody().timestamp()).isNotNull();
    assertThat(response.getBody().errors()).isNull();
  }

  @Test
  void accessDeniedBecomes403() {
    ResponseEntity<ErrorResponse> response =
        handler.handleAccessDenied(new AccessDeniedException("denied"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().code()).isEqualTo("ACCESS_DENIED");
  }

  @Test
  void unexpectedExceptionHidesDetails() {
    ResponseEntity<ErrorResponse> response =
        handler.handleException(new IllegalStateException("db down: secret detail"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().message()).doesNotContain("secret detail");
  }
}
