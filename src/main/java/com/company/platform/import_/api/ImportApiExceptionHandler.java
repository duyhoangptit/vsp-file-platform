package com.company.platform.import_.api;

import com.company.platform.job.domain.JobLimitExceededException;
import com.company.platform.job.domain.JobNotFoundException;
import com.company.platform.job.domain.JobRateLimitExceededException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.company.platform.import_.api")
public class ImportApiExceptionHandler {

  @ExceptionHandler(JobNotFoundException.class)
  public ResponseEntity<ApiError> handleJobNotFound(JobNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(JobLimitExceededException.class)
  public ResponseEntity<ApiError> handleJobLimitExceeded(JobLimitExceededException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(JobRateLimitExceededException.class)
  public ResponseEntity<ApiError> handleJobRateLimitExceeded(JobRateLimitExceededException e) {
    return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
  }

  private ResponseEntity<ApiError> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ApiError(status.value(), message, Instant.now()));
  }

  public record ApiError(int status, String message, Instant timestamp) {}
}
