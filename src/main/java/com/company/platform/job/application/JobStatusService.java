package com.company.platform.job.application;

import com.company.platform.job.domain.InvalidJobStateException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.auth.ResourceType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobStatusService {

  private final JobRepository jobRepository;
  private final AuditLogService auditLogService;

  public JobStatusService(JobRepository jobRepository, AuditLogService auditLogService) {
    this.jobRepository = jobRepository;
    this.auditLogService = auditLogService;
  }

  @Transactional
  public boolean tryMarkProcessing(UUID jobId) {
    boolean success =
        jobRepository.casUpdateStatus(jobId, JobStatus.PENDING, JobStatus.PROCESSING, Instant.now())
            == 1;
    if (success) {
      auditLogService.record(
          null, "JOB_PROCESSING_STARTED", ResourceType.JOB.name(), jobId, AuditResult.SUCCESS);
    }
    return success;
  }

  @Transactional
  public void heartbeat(UUID jobId) {
    jobRepository.casUpdateHeartbeat(jobId, JobStatus.PROCESSING, Instant.now());
  }

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
    auditLogService.record(null, "JOB_DONE", ResourceType.JOB.name(), jobId, AuditResult.SUCCESS);
  }

  @Transactional
  public void markFailed(UUID jobId, String errorMessage) {
    int updated =
        jobRepository.casUpdateStatusToFailed(
            jobId, JobStatus.PROCESSING, JobStatus.FAILED, errorMessage, Instant.now());
    if (updated != 1) {
      throw new InvalidJobStateException(jobId, JobStatus.PROCESSING);
    }
    auditLogService.record(null, "JOB_FAILED", ResourceType.JOB.name(), jobId, AuditResult.FAILED);
  }
}
