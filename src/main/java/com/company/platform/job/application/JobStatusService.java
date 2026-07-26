package com.company.platform.job.application;

import com.company.platform.job.domain.InvalidJobStateException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.Audited;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobStatusService {

  private final JobRepository jobRepository;

  public JobStatusService(JobRepository jobRepository) {
    this.jobRepository = jobRepository;
  }

  @Audited(action = "JOB_PROCESSING_STARTED", resourceType = "JOB", result = AuditResult.SUCCESS)
  @Transactional
  public boolean tryMarkProcessing(UUID jobId) {
    return jobRepository.casUpdateStatus(
            jobId, JobStatus.PENDING, JobStatus.PROCESSING, Instant.now())
        == 1;
  }

  @Transactional
  public void heartbeat(UUID jobId) {
    jobRepository.casUpdateHeartbeat(jobId, JobStatus.PROCESSING, Instant.now());
  }

  // Best-effort: không throw nếu job không còn PROCESSING (VD vừa bị markFailed) — đây chỉ là
  // chỉ số tiến độ hiển thị cho UI (mục 11 design doc), không phải state transition bắt buộc.
  @Transactional
  public void updateProgress(UUID jobId, long processedUnits) {
    jobRepository.updateProgress(jobId, JobStatus.PROCESSING, processedUnits, Instant.now());
  }

  @Audited(action = "JOB_DONE", resourceType = "JOB", result = AuditResult.SUCCESS)
  @Transactional
  public void markDone(UUID jobId, String resultLocation, long processedUnits) {
    int updated =
        jobRepository.casUpdateStatusToDone(
            jobId,
            JobStatus.PROCESSING,
            JobStatus.DONE,
            resultLocation,
            processedUnits,
            Instant.now());
    if (updated != 1) {
      throw new InvalidJobStateException(jobId, JobStatus.PROCESSING);
    }
  }

  @Audited(action = "JOB_FAILED", resourceType = "JOB", result = AuditResult.FAILED)
  @Transactional
  public void markFailed(UUID jobId, String errorMessage) {
    int updated =
        jobRepository.casUpdateStatusToFailed(
            jobId, JobStatus.PROCESSING, JobStatus.FAILED, errorMessage, Instant.now());
    if (updated != 1) {
      throw new InvalidJobStateException(jobId, JobStatus.PROCESSING);
    }
  }
}
