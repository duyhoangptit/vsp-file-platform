package com.company.platform.job.domain;

import java.util.UUID;

public class JobRateLimitExceededException extends RuntimeException {

  public JobRateLimitExceededException(UUID userId, JobType jobType) {
    super("User %s đã vượt rate limit tạo job loại %s, thử lại sau".formatted(userId, jobType));
  }
}
