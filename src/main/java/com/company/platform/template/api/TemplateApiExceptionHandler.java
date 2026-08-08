package com.company.platform.template.api;

import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileCategoryNotFoundException;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.FileVerificationFailedException;
import com.company.platform.file.domain.InvalidFileAssetStateException;
import com.company.platform.template.domain.FileTemplateNotFoundException;
import com.company.platform.template.domain.TemplateVersionLockTimeoutException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Xử lý riêng cho package template.api — FileApiExceptionHandler chỉ scope basePackages =
 * "com.company.platform.file.api" nên không bắt được exception ném ra từ file module khi nó được
 * gọi xuyên qua FileTemplateController (initiate/confirm đều delegate sang FileUploadService).
 */
@RestControllerAdvice(basePackages = "com.company.platform.template.api")
public class TemplateApiExceptionHandler {

  @ExceptionHandler(FileTemplateNotFoundException.class)
  public ResponseEntity<ApiError> handleTemplateNotFound(FileTemplateNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(TemplateVersionLockTimeoutException.class)
  public ResponseEntity<ApiError> handleVersionLockTimeout(TemplateVersionLockTimeoutException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

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

  private ResponseEntity<ApiError> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ApiError(status.value(), message, Instant.now()));
  }

  public record ApiError(int status, String message, Instant timestamp) {}
}
