package com.company.platform.job.processor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

class AbstractJobProcessorTest {

  private final JobStatusService jobStatusService = mock(JobStatusService.class);
  private final TaskScheduler heartbeatScheduler = mock(TaskScheduler.class);
  private final ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);

  @Test
  void should_notCallDoExecute_when_tryMarkProcessingLosesTheRace() {
    UUID jobId = UUID.randomUUID();
    when(jobStatusService.tryMarkProcessing(jobId)).thenReturn(false);
    Function<JobContext, JobResult> doExecute = mock(Function.class);
    TestJobProcessor processor = new TestJobProcessor(() -> {}, doExecute);

    processor.process(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    verify(doExecute, never()).apply(any());
    verify(jobStatusService, never()).markDone(any(), any(), anyLong());
  }

  @Test
  void should_callMarkDone_when_processCompletesSuccessfully() {
    UUID jobId = UUID.randomUUID();
    stubHeartbeatScheduler();
    when(jobStatusService.tryMarkProcessing(jobId)).thenReturn(true);
    JobResult result = new JobResult("bucket/result.csv", 42);
    TestJobProcessor processor = new TestJobProcessor(() -> {}, context -> result);

    processor.process(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    verify(jobStatusService).markDone(jobId, "bucket/result.csv", 42);
    verify(jobStatusService, never()).markFailed(eq(jobId), any());
    verify(heartbeatFuture).cancel(false);
  }

  @Test
  void should_callJobStatusServiceUpdateProgress_when_doExecuteCallsContextUpdateProgress() {
    UUID jobId = UUID.randomUUID();
    stubHeartbeatScheduler();
    when(jobStatusService.tryMarkProcessing(jobId)).thenReturn(true);
    JobResult result = new JobResult("bucket/result.csv", 42);
    TestJobProcessor processor =
        new TestJobProcessor(
            () -> {},
            context -> {
              context.updateProgress(10);
              context.updateProgress(20);
              return result;
            });

    processor.process(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    verify(jobStatusService).updateProgress(jobId, 10);
    verify(jobStatusService).updateProgress(jobId, 20);
  }

  @Test
  void should_callMarkFailed_when_validateThrows() {
    UUID jobId = UUID.randomUUID();
    stubHeartbeatScheduler();
    when(jobStatusService.tryMarkProcessing(jobId)).thenReturn(true);
    Function<JobContext, JobResult> doExecute = mock(Function.class);
    TestJobProcessor processor =
        new TestJobProcessor(
            () -> {
              throw new IllegalArgumentException("payload không hợp lệ");
            },
            doExecute);

    processor.process(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    verify(doExecute, never()).apply(any());
    verify(jobStatusService).markFailed(jobId, "payload không hợp lệ");
    verify(heartbeatFuture).cancel(false);
  }

  @Test
  void should_callMarkFailed_when_doExecuteThrows() {
    UUID jobId = UUID.randomUUID();
    stubHeartbeatScheduler();
    when(jobStatusService.tryMarkProcessing(jobId)).thenReturn(true);
    TestJobProcessor processor =
        new TestJobProcessor(
            () -> {},
            context -> {
              throw new RuntimeException("lỗi khi export");
            });

    processor.process(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    verify(jobStatusService).markFailed(jobId, "lỗi khi export");
    verify(heartbeatFuture).cancel(false);
  }

  @Test
  void should_notPropagateException_when_markFailedItselfThrows() {
    UUID jobId = UUID.randomUUID();
    stubHeartbeatScheduler();
    when(jobStatusService.tryMarkProcessing(jobId)).thenReturn(true);
    doThrow(new RuntimeException("job đã bị reconciler đổi trạng thái"))
        .when(jobStatusService)
        .markFailed(any(), any());
    TestJobProcessor processor =
        new TestJobProcessor(
            () -> {
              throw new IllegalStateException("lỗi nghiệp vụ");
            },
            mock(Function.class));

    processor.process(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    verify(heartbeatFuture).cancel(false);
  }

  private void stubHeartbeatScheduler() {
    doReturn(heartbeatFuture)
        .when(heartbeatScheduler)
        .scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
  }

  private class TestJobProcessor extends AbstractJobProcessor {

    private final Runnable validateAction;
    private final Function<JobContext, JobResult> executeAction;

    TestJobProcessor(Runnable validateAction, Function<JobContext, JobResult> executeAction) {
      // Runnable::run -- chạy đồng bộ ngay trên thread gọi process(), giữ nguyên các
      // assertion đồng bộ hiện có (test này verify hành vi Template Method, không phải
      // cơ chế executor riêng).
      super(jobStatusService, heartbeatScheduler, Runnable::run);
      this.validateAction = validateAction;
      this.executeAction = executeAction;
    }

    @Override
    public JobType supports() {
      return JobType.EXPORT_USER_CSV;
    }

    @Override
    protected void validate(Map<String, Object> payload) {
      validateAction.run();
    }

    @Override
    protected JobResult doExecute(JobContext context) {
      return executeAction.apply(context);
    }
  }
}
