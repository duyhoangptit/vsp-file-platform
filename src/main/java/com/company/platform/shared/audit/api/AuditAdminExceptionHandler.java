package com.company.platform.shared.audit.api;

import com.company.platform.shared.pagination.InvalidCursorException;
import com.company.platform.shared.pagination.InvalidTimeRangeException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.company.platform.shared.audit.api")
public class AuditAdminExceptionHandler {

  @ExceptionHandler({InvalidCursorException.class, InvalidTimeRangeException.class})
  public ResponseEntity<ApiError> handleBadQuery(RuntimeException e) {
    return error(e.getMessage());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return error("Tham số không hợp lệ: " + e.getName());
  }

  private ResponseEntity<ApiError> error(String message) {
    return ResponseEntity.badRequest()
        .body(new ApiError(HttpStatus.BAD_REQUEST.value(), message, Instant.now()));
  }

  public record ApiError(int status, String message, Instant timestamp) {}
}
