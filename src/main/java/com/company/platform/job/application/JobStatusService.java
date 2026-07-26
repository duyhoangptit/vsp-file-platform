package com.company.platform.job.application;

import com.company.platform.job.domain.InvalidJobStateException;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.Audited;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobStatusService {

  private final JobRepository jobRepository;
  private final MeterRegistry meterRegistry;

  // Correlate tryMarkProcessing (CAS win) -> markDone/markFailed để đo thời gian PROCESSING
  // thực tế, không dùng updatedAt vì updateProgress() cũng cập nhật updatedAt định kỳ trong
  // lúc doExecute() chạy (mục 11 design doc), làm sai lệch mốc bắt đầu PROCESSING. An toàn
  // bounded/không leak vì doExecute() luôn chạy trên cùng instance đã thắng CAS (executor nội
  // bộ, không giao job cho instance khác) và mỗi job thắng CAS đúng 1 lần luôn kết thúc bằng
  // đúng 1 trong markDone/markFailed, entry luôn được remove ở 1 trong 2 nhánh đó.
  private final Map<UUID, Timer.Sample> processingSamples = new ConcurrentHashMap<>();

  public JobStatusService(JobRepository jobRepository, MeterRegistry meterRegistry) {
    this.jobRepository = jobRepository;
    this.meterRegistry = meterRegistry;
  }

  @Audited(action = "JOB_PROCESSING_STARTED", resourceType = "JOB", result = AuditResult.SUCCESS)
  @Transactional
  public boolean tryMarkProcessing(UUID jobId) {
    boolean won =
        jobRepository.casUpdateStatus(jobId, JobStatus.PENDING, JobStatus.PROCESSING, Instant.now())
            == 1;
    if (won) {
      processingSamples.put(jobId, Timer.start(meterRegistry));
    }
    return won;
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
    String jobType = jobTypeTag(jobId);
    meterRegistry.counter("job.done", "job_type", jobType).increment();
    Timer.Sample sample = processingSamples.remove(jobId);
    if (sample != null) {
      sample.stop(
          Timer.builder("job.processing.duration")
              .tag("job_type", jobType)
              .register(meterRegistry));
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
    meterRegistry.counter("job.failed", "job_type", jobTypeTag(jobId)).increment();
    processingSamples.remove(jobId);
  }

  private String jobTypeTag(UUID jobId) {
    JobType jobType = jobRepository.findById(jobId).map(Job::getJobType).orElse(null);
    return jobType != null ? jobType.name() : "UNKNOWN";
  }
}
