package com.company.platform.job.processor;

import com.company.platform.job.application.JobStatusService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

public abstract class AbstractJobProcessor implements JobProcessor {

  private static final Logger log = LoggerFactory.getLogger(AbstractJobProcessor.class);
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

  private final JobStatusService jobStatusService;
  private final TaskScheduler heartbeatScheduler;
  private final Executor jobExecutionExecutor;

  protected AbstractJobProcessor(
      JobStatusService jobStatusService,
      TaskScheduler heartbeatScheduler,
      Executor jobExecutionExecutor) {
    this.jobStatusService = jobStatusService;
    this.heartbeatScheduler = heartbeatScheduler;
    this.jobExecutionExecutor = jobExecutionExecutor;
  }

  // CAS tryMarkProcessing chạy đồng bộ trên thread gọi (nhanh, 1 câu UPDATE) rồi trả về ngay
  // — không đợi doExecute(). Phần việc thật (heartbeat/validate/doExecute/markDone-Failed)
  // chạy trên jobExecutionExecutor, tách khỏi thread gọi (Kafka listener thread / fallback
  // poller thread) để không block partition/poller khi job chạy lâu (mục 6.4).
  @Override
  public final void process(JobContext context) {
    if (!jobStatusService.tryMarkProcessing(context.jobId())) {
      return;
    }
    jobExecutionExecutor.execute(() -> runClaimedJob(context));
  }

  private void runClaimedJob(JobContext context) {
    ScheduledFuture<?> heartbeatTask = startHeartbeat(context.jobId());
    try {
      validate(context.payload());
      JobResult result = doExecute(context);
      jobStatusService.markDone(context.jobId(), result.resultLocation(), result.processedUnits());
    } catch (Exception e) {
      try {
        jobStatusService.markFailed(context.jobId(), e.getMessage());
      } catch (RuntimeException markFailedFailure) {
        log.warn(
            "Không thể set job {} sang FAILED: {}",
            context.jobId(),
            markFailedFailure.getMessage());
      }
    } finally {
      heartbeatTask.cancel(false);
    }
  }

  protected abstract void validate(Map<String, Object> payload);

  protected abstract JobResult doExecute(JobContext context);

  private ScheduledFuture<?> startHeartbeat(UUID jobId) {
    return heartbeatScheduler.scheduleAtFixedRate(
        () -> jobStatusService.heartbeat(jobId),
        Instant.now().plus(HEARTBEAT_INTERVAL),
        HEARTBEAT_INTERVAL);
  }
}
