package com.company.platform.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.job.domain.InvalidJobStateException;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.audit.AuditLog;
import com.company.platform.shared.audit.AuditLogRepository;
import com.company.platform.shared.audit.AuditResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class JobStatusServiceIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private JobStatusService jobStatusService;
  @Autowired private JobRepository jobRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  private UUID seedPendingJob() {
    UUID jobId = UUID.randomUUID();
    jobRepository.save(
        new Job(
            jobId,
            JobType.EXPORT_USER_CSV,
            Map.of("filter", "active"),
            null,
            null,
            null,
            UUID.randomUUID()));
    return jobId;
  }

  private UUID seedProcessingJob() {
    UUID jobId = seedPendingJob();
    jobStatusService.tryMarkProcessing(jobId);
    return jobId;
  }

  @Test
  void should_transitionToProcessing_when_jobIsPending() {
    UUID jobId = seedPendingJob();

    boolean success = jobStatusService.tryMarkProcessing(jobId);

    assertThat(success).isTrue();
    assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
        .isEqualTo(JobStatus.PROCESSING);
    assertThat(auditLogsFor(jobId, "JOB_PROCESSING_STARTED"))
        .singleElement()
        .satisfies(log -> assertThat(log.getResult()).isEqualTo(AuditResult.SUCCESS));
  }

  @Test
  void should_notTransitionToProcessing_when_jobIsNotPending() {
    UUID jobId = seedProcessingJob();

    boolean success = jobStatusService.tryMarkProcessing(jobId);

    assertThat(success).isFalse();
  }

  @Test
  void should_transitionToDone_when_jobIsProcessing() {
    UUID jobId = seedProcessingJob();

    jobStatusService.markDone(jobId, "bucket/result.csv", 10);

    Job job = jobRepository.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(job.getResultLocation()).isEqualTo("bucket/result.csv");
    assertThat(job.getProcessedUnits()).isEqualTo(10);
    assertThat(auditLogsFor(jobId, "JOB_DONE"))
        .singleElement()
        .satisfies(log -> assertThat(log.getResult()).isEqualTo(AuditResult.SUCCESS));
  }

  @Test
  void should_throwInvalidJobStateException_when_markDoneOnNonProcessingJob() {
    UUID jobId = seedPendingJob();

    assertThatThrownBy(() -> jobStatusService.markDone(jobId, "bucket/result.csv", 10))
        .isInstanceOf(InvalidJobStateException.class);
    assertThat(auditLogsFor(jobId, "JOB_DONE")).isEmpty();
  }

  @Test
  void should_transitionToFailed_when_jobIsProcessing() {
    UUID jobId = seedProcessingJob();

    jobStatusService.markFailed(jobId, "lỗi khi export");

    Job job = jobRepository.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getErrorMessage()).isEqualTo("lỗi khi export");
    assertThat(auditLogsFor(jobId, "JOB_FAILED"))
        .singleElement()
        .satisfies(log -> assertThat(log.getResult()).isEqualTo(AuditResult.FAILED));
  }

  @Test
  void should_throwInvalidJobStateException_when_markFailedOnNonProcessingJob() {
    UUID jobId = seedPendingJob();

    assertThatThrownBy(() -> jobStatusService.markFailed(jobId, "lỗi khi export"))
        .isInstanceOf(InvalidJobStateException.class);
    assertThat(auditLogsFor(jobId, "JOB_FAILED")).isEmpty();
  }

  @Test
  void should_updateHeartbeat_when_jobIsProcessing() {
    UUID jobId = seedProcessingJob();

    jobStatusService.heartbeat(jobId);

    assertThat(jobRepository.findById(jobId).orElseThrow().getHeartbeatAt()).isNotNull();
  }

  @Test
  void should_notUpdateHeartbeat_when_jobIsNotProcessing() {
    UUID jobId = seedPendingJob();

    jobStatusService.heartbeat(jobId);

    assertThat(jobRepository.findById(jobId).orElseThrow().getHeartbeatAt()).isNull();
  }

  @Test
  void should_updateProcessedUnits_when_jobIsProcessing() {
    UUID jobId = seedProcessingJob();

    jobStatusService.updateProgress(jobId, 250);

    assertThat(jobRepository.findById(jobId).orElseThrow().getProcessedUnits()).isEqualTo(250);
  }

  @Test
  void should_notUpdateProcessedUnits_when_jobIsNotProcessing() {
    UUID jobId = seedPendingJob();

    jobStatusService.updateProgress(jobId, 250);

    assertThat(jobRepository.findById(jobId).orElseThrow().getProcessedUnits()).isEqualTo(0);
  }

  @Test
  void should_onlyOneWorkerSucceedAndSkipProcessing_when_twoWorkersRaceOnSamePendingJob()
      throws Exception {
    UUID jobId = seedPendingJob();
    int workerCount = 2;
    CyclicBarrier startBarrier = new CyclicBarrier(workerCount);
    AtomicInteger processedByWorkerCount = new AtomicInteger(0);
    ExecutorService executor = Executors.newFixedThreadPool(workerCount);
    try {
      Callable<Boolean> worker =
          () -> {
            // Chờ cả 2 worker sẵn sàng rồi mới gọi tryMarkProcessing() gần như cùng lúc,
            // để bắt đúng race condition trên CAS thay vì 2 lệnh gọi tuần tự.
            startBarrier.await(10, TimeUnit.SECONDS);
            boolean acquired = jobStatusService.tryMarkProcessing(jobId);
            if (acquired) {
              // Chỉ worker thắng race mới được xử lý job — mô phỏng đúng guard
              // "if (!tryMarkProcessing(...)) return;" trong AbstractJobProcessor.process().
              processedByWorkerCount.incrementAndGet();
            }
            return acquired;
          };

      List<Future<Boolean>> futures = List.of(executor.submit(worker), executor.submit(worker));
      List<Boolean> results = futures.stream().map(this::getUnchecked).toList();

      assertThat(results).containsExactlyInAnyOrder(true, false);
      assertThat(processedByWorkerCount.get()).isEqualTo(1);
      assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
          .isEqualTo(JobStatus.PROCESSING);
      assertThat(auditLogsFor(jobId, "JOB_PROCESSING_STARTED")).hasSize(1);
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private List<AuditLog> auditLogsFor(UUID jobId, String action) {
    return auditLogRepository.findByResourceId(jobId).stream()
        .filter(log -> log.getAction().equals(action))
        .toList();
  }

  private boolean getUnchecked(Future<Boolean> future) {
    try {
      return future.get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
