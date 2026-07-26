package com.company.platform.job.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration-level (không mock JobStatusService/TaskScheduler như AbstractJobProcessorTest): dùng
 * DB Postgres thật + TaskScheduler thật để verify khi doExecute() lỗi giữa chừng, job thật sự
 * chuyển FAILED với error_message có nội dung trong DB, và ScheduledFuture của heartbeat thật sự bị
 * cancel (không chỉ verify cancel() được gọi trên mock).
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class AbstractJobProcessorFailureIT {

  private static final String FAILURE_MESSAGE = "Lỗi giả lập giữa chừng khi xử lý job";

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    // Test tự gọi process() trực tiếp -- đặt rất dài để fallback poller chắc chắn không đua
    // dispatch job trong lúc test chạy.
    registry.add("app.job.fallback-poller.interval", () -> "5m");
  }

  @Autowired private FailingJobProcessor failingJobProcessor;
  @Autowired private JobRepository jobRepository;
  @Autowired private CapturingTaskScheduler capturingTaskScheduler;

  @BeforeEach
  void cleanJobsTable() {
    jobRepository.deleteAll();
    capturingTaskScheduler.reset();
  }

  @Test
  void should_transitionToFailedWithErrorMessage_and_cancelHeartbeat_when_doExecuteThrowsMidway() {
    UUID jobId = UUID.randomUUID();
    jobRepository.save(
        new Job(jobId, JobType.IMPORT_PRODUCT, Map.of(), null, null, null, UUID.randomUUID()));

    failingJobProcessor.process(new JobContext(jobId, JobType.IMPORT_PRODUCT, Map.of()));

    Job job = jobRepository.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getErrorMessage()).contains(FAILURE_MESSAGE);
    // Tick heartbeat đầu tiên cách lúc bắt đầu 30s (HEARTBEAT_INTERVAL, hardcode trong
    // AbstractJobProcessor) -- job lỗi ngay trong vài chục ms nên chưa kịp có tick nào.
    assertThat(job.getHeartbeatAt()).isNull();

    ScheduledFuture<?> heartbeatFuture = capturingTaskScheduler.lastHeartbeatFuture();
    assertThat(heartbeatFuture != null)
        .as("heartbeat task phải thực sự được đăng ký qua TaskScheduler thật")
        .isTrue();
    assertThat(heartbeatFuture.isCancelled())
        .as("heartbeat task phải được huỷ khi job FAILED, không rò rỉ chạy nền")
        .isTrue();
  }

  @TestConfiguration
  static class FailingProcessorConfig {

    @Bean
    CapturingTaskScheduler capturingTaskScheduler() {
      CapturingTaskScheduler scheduler = new CapturingTaskScheduler();
      scheduler.setPoolSize(2);
      scheduler.initialize();
      return scheduler;
    }

    @Bean
    FailingJobProcessor failingJobProcessor(
        JobStatusService jobStatusService, TaskScheduler capturingTaskScheduler) {
      return new FailingJobProcessor(jobStatusService, capturingTaskScheduler);
    }
  }

  /**
   * TaskScheduler thật (ThreadPoolTaskScheduler), chỉ thêm khả năng lưu lại ScheduledFuture cuối
   * cùng mà heartbeat của AbstractJobProcessor đăng ký, để test verify isCancelled() trực tiếp thay
   * vì phải đợi hết HEARTBEAT_INTERVAL (30s) mới suy luận gián tiếp.
   */
  static class CapturingTaskScheduler extends ThreadPoolTaskScheduler {

    private final AtomicReference<ScheduledFuture<?>> lastHeartbeatFuture = new AtomicReference<>();

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable task, Instant startTime, Duration period) {
      ScheduledFuture<?> future = super.scheduleAtFixedRate(task, startTime, period);
      lastHeartbeatFuture.set(future);
      return future;
    }

    ScheduledFuture<?> lastHeartbeatFuture() {
      return lastHeartbeatFuture.get();
    }

    void reset() {
      lastHeartbeatFuture.set(null);
    }
  }

  static class FailingJobProcessor extends AbstractJobProcessor {

    FailingJobProcessor(JobStatusService jobStatusService, TaskScheduler heartbeatScheduler) {
      // Runnable::run -- test này verify CAS/heartbeat/error-handling ngay sau process(),
      // không phải cơ chế executor riêng (đã có JobDispatchKafkaConcurrencyIT lo việc đó).
      super(jobStatusService, heartbeatScheduler, Runnable::run);
    }

    @Override
    public JobType supports() {
      return JobType.IMPORT_PRODUCT;
    }

    @Override
    protected void validate(Map<String, Object> payload) {}

    @Override
    protected JobResult doExecute(JobContext context) {
      try {
        // Giả lập đã xử lý được 1 phần việc trước khi gặp lỗi ("giữa chừng"), không phải lỗi
        // ngay từ đầu.
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException(FAILURE_MESSAGE);
    }
  }
}
