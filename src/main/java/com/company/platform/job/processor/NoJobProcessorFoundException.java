package com.company.platform.job.processor;

import com.company.platform.job.domain.JobType;

public class NoJobProcessorFoundException extends RuntimeException {

  public NoJobProcessorFoundException(JobType jobType) {
    super("Không tìm thấy JobProcessor nào cho loại job: " + jobType);
  }
}
