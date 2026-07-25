package com.company.platform.job.domain;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

  public JobNotFoundException(UUID jobId) {
    super("Không tìm thấy job: " + jobId);
  }
}
