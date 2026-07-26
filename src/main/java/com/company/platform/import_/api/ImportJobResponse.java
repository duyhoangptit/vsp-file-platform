package com.company.platform.import_.api;

import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobStatus;
import java.time.Instant;
import java.util.UUID;

public record ImportJobResponse(
    UUID id,
    JobStatus status,
    Long totalUnits,
    long processedUnits,
    String resultLocation,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt) {

  public static ImportJobResponse from(Job job) {
    return new ImportJobResponse(
        job.getId(),
        job.getStatus(),
        job.getTotalUnits(),
        job.getProcessedUnits(),
        job.getResultLocation(),
        job.getErrorMessage(),
        job.getCreatedAt(),
        job.getUpdatedAt());
  }
}
