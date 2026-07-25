package com.company.platform.job.domain;

import java.util.UUID;

public class JobLimitExceededException extends RuntimeException {

  public JobLimitExceededException(UUID userId, int maxActivePerUser) {
    super(
        "User %s đã có đủ %d job đang PENDING/PROCESSING, không thể tạo thêm"
            .formatted(userId, maxActivePerUser));
  }
}
