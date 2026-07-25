package com.company.platform.shared.auth;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

  @ExceptionHandler(UnauthenticatedException.class)
  public ResponseEntity<ApiError> handleUnauthenticated(UnauthenticatedException e) {
    return error(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
    return error(HttpStatus.FORBIDDEN, e.getMessage());
  }

  private ResponseEntity<ApiError> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ApiError(status.value(), message, Instant.now()));
  }

  public record ApiError(int status, String message, Instant timestamp) {}
}
