package com.company.platform.file.api;

import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileCategoryNotFoundException;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.FileVerificationFailedException;
import com.company.platform.file.domain.InvalidFileAssetStateException;
import com.company.platform.file.domain.InvalidPartNumberException;
import com.company.platform.file.domain.MultipartNotEnabledException;
import com.company.platform.file.domain.StaleVersionException;
import com.company.platform.shared.pagination.InvalidCursorException;
import com.company.platform.shared.pagination.InvalidTimeRangeException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.company.platform.file.api")
public class FileApiExceptionHandler {

  @ExceptionHandler(FileCategoryNotFoundException.class)
  public ResponseEntity<ApiError> handleCategoryNotFound(FileCategoryNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(FileAssetNotFoundException.class)
  public ResponseEntity<ApiError> handleAssetNotFound(FileAssetNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(FileValidationException.class)
  public ResponseEntity<ApiError> handleValidation(FileValidationException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(InvalidFileAssetStateException.class)
  public ResponseEntity<ApiError> handleInvalidState(InvalidFileAssetStateException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(FileVerificationFailedException.class)
  public ResponseEntity<ApiError> handleVerificationFailed(FileVerificationFailedException e) {
    return error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
  }

  @ExceptionHandler(FileNotAvailableForDownloadException.class)
  public ResponseEntity<ApiError> handleNotAvailableForDownload(
      FileNotAvailableForDownloadException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(MultipartNotEnabledException.class)
  public ResponseEntity<ApiError> handleMultipartNotEnabled(MultipartNotEnabledException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(InvalidPartNumberException.class)
  public ResponseEntity<ApiError> handleInvalidPartNumber(InvalidPartNumberException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(StaleVersionException.class)
  public ResponseEntity<ApiError> handleStaleVersion(StaleVersionException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  // version khớp lúc đọc nhưng có người commit trước lúc flush (race hẹp) — cùng nghĩa 409.
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
    return error(HttpStatus.CONFLICT, "Dữ liệu vừa bị người khác thay đổi — tải lại rồi thử lại");
  }

  @ExceptionHandler({InvalidCursorException.class, InvalidTimeRangeException.class})
  public ResponseEntity<ApiError> handleBadQuery(RuntimeException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e) {
    return error(
        HttpStatus.BAD_REQUEST, "Body không hợp lệ: " + e.getBindingResult().getFieldErrors());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return error(HttpStatus.BAD_REQUEST, "Tham số không hợp lệ: " + e.getName());
  }

  private ResponseEntity<ApiError> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ApiError(status.value(), message, Instant.now()));
  }

  public record ApiError(int status, String message, Instant timestamp) {}
}
