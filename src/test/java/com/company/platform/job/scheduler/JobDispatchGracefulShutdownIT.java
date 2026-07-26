package com.company.platform.job.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.messaging.JobMessage;
import com.company.platform.job.messaging.kafka.KafkaJobEventPublisher;
import com.company.platform.job.processor.AbstractJobProcessor;
import com.company.platform.job.processor.JobContext;
import com.company.platform.job.processor.JobResult;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Chứng minh graceful shutdown thật: N job đang PROCESSING (chạy thật trên jobExecutionExecutor,
 * không phải Runnable::run) khi shutdown bắt đầu vẫn hoàn tất đúng hạn, trong khi message MỚI
 * publish sau khi listener đã dừng không hề được dispatch (job của nó không bao giờ rời PENDING).
 * Gọi trực tiếp JobDispatchShutdownCoordinator.stop() (bean thật) thay vì đóng cả
 * ApplicationContext — việc Spring gọi đúng SmartLifecycle.stop() khi context đóng là cơ chế chuẩn
 * của framework, không cần tự chứng minh lại ở đây; điều cần verify là logic BÊN TRONG stop() của
 * coordinator.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
// coordinator.stop() thật sự shutdown() executor + dừng Kafka listener của context này -- không
// để Spring test context cache tái sử dụng context đã "chết" một phần cho test khác.
@DirtiesContext
class JobDispatchGracefulShutdownIT {

  private static final String TOPIC = "job-graceful-shutdown-it";
  private static final String GROUP_ID = "job-graceful-shutdown-it-group";
  private static final Duration DO_EXECUTE_DURATION = Duration.ofSeconds(3);

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.0");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("messaging.provider", () -> "kafka");
    registry.add("app.job.kafka.topic", () -> TOPIC);
    registry.add("spring.kafka.consumer.group-id", () -> GROUP_ID);
    registry.add("app.job.fallback-poller.interval", () -> "5m");
    registry.add("app.job.execution.pool-size", () -> 4);
    registry.add("app.job.execution.shutdown-timeout-seconds", () -> 10);
  }

  @Autowired private JobDispatchShutdownCoordinator coordinator;
  @Autowired private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
  @Autowired private KafkaJobEventPublisher jobEventPublisher;
  @Autowired private JobRepository jobRepository;

  @BeforeEach
  void cleanJobsTable() {
    jobRepository.deleteAll();
  }

  @Test
  void should_completeInFlightJobs_and_rejectNewMessages_when_gracefullyShuttingDown()
      throws Exception {
    UUID job1 = seedPendingJob();
    UUID job2 = seedPendingJob();
    UUID job3 = seedPendingJob();

    jobEventPublisher.publish(new JobMessage(job1, JobType.IMPORT_PRODUCT));
    jobEventPublisher.publish(new JobMessage(job2, JobType.IMPORT_PRODUCT));
    jobEventPublisher.publish(new JobMessage(job3, JobType.IMPORT_PRODUCT));

    pollUntilStatus(job1, JobStatus.PROCESSING, Duration.ofSeconds(10));
    pollUntilStatus(job2, JobStatus.PROCESSING, Duration.ofSeconds(10));
    pollUntilStatus(job3, JobStatus.PROCESSING, Duration.ofSeconds(10));

    Thread shutdownThread = new Thread(coordinator::stop, "test-shutdown");
    shutdownThread.start();

    // Xác nhận listener đã dừng THẬT trước khi thử "lẻn" message mới -- tránh phụ thuộc canh
    // thời gian may rủi.
    pollUntilListenerStopped(Duration.ofSeconds(10));

    UUID sneakJob = seedPendingJob();
    jobEventPublisher.publish(new JobMessage(sneakJob, JobType.IMPORT_PRODUCT));

    shutdownThread.join(Duration.ofSeconds(15).toMillis());
    assertThat(shutdownThread.isAlive()).as("coordinator.stop() phải kết thúc").isFalse();

    Job doneJob1 = jobRepository.findById(job1).orElseThrow();
    Job doneJob2 = jobRepository.findById(job2).orElseThrow();
    Job doneJob3 = jobRepository.findById(job3).orElseThrow();
    assertThat(doneJob1.getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(doneJob2.getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(doneJob3.getStatus()).isEqualTo(JobStatus.DONE);

    Job sneak = jobRepository.findById(sneakJob).orElseThrow();
    assertThat(sneak.getStatus())
        .as("message publish sau khi listener đã dừng không được dispatch")
        .isEqualTo(JobStatus.PENDING);
  }

  private UUID seedPendingJob() {
    UUID jobId = UUID.randomUUID();
    jobRepository.save(
        new Job(jobId, JobType.IMPORT_PRODUCT, Map.of(), null, null, null, UUID.randomUUID()));
    return jobId;
  }

  private void pollUntilStatus(UUID jobId, JobStatus target, Duration window)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + window.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Optional<Job> job = jobRepository.findById(jobId);
      if (job.isPresent() && job.get().getStatus() == target) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Job " + jobId + " không đạt " + target + " trong " + window);
  }

  private void pollUntilListenerStopped(Duration window) throws InterruptedException {
    long deadline = System.currentTimeMillis() + window.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (!kafkaListenerEndpointRegistry.isRunning()) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Kafka listener registry không dừng trong " + window);
  }

  @TestConfiguration
  static class SlowImportProductProcessorConfig {

    @Bean
    AbstractJobProcessor slowImportProductProcessor(
        JobStatusService jobStatusService,
        TaskScheduler heartbeatScheduler,
        Executor jobExecutionExecutor) {
      // Dùng jobExecutionExecutor thật (không phải Runnable::run) -- job phải thật sự chạy nền
      // để test này mô phỏng đúng "N job đang PROCESSING tại thời điểm shutdown bắt đầu".
      return new AbstractJobProcessor(jobStatusService, heartbeatScheduler, jobExecutionExecutor) {
        @Override
        public JobType supports() {
          return JobType.IMPORT_PRODUCT;
        }

        @Override
        protected void validate(Map<String, Object> payload) {}

        @Override
        protected JobResult doExecute(JobContext context) {
          try {
            Thread.sleep(DO_EXECUTE_DURATION.toMillis());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
          return new JobResult("bucket/fake-result.csv", 1);
        }
      };
    }
  }
}
