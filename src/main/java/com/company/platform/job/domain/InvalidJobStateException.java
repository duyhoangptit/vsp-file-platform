package com.company.platform.job.domain;

import java.util.UUID;

public class InvalidJobStateException extends RuntimeException {

  public InvalidJobStateException(UUID jobId, JobStatus expectedStatus) {
    super(
        "Job %s không ở trạng thái %s, không thể thực hiện thao tác này"
            .formatted(jobId, expectedStatus));
  }
}
