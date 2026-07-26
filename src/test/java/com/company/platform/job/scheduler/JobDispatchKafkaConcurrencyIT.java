package com.company.platform.job.scheduler;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Chứng minh: Kafka consumer thread vẫn poll/ack được message tiếp theo trong khi job của message
 * trước đó còn đang PROCESSING trên jobExecutionExecutor riêng (không phải thread @KafkaListener).
 * Nếu AbstractJobProcessor.process() còn block tới khi doExecute() xong (thiết kế cũ), job2 sẽ
 * không bao giờ đạt PROCESSING trong lúc job1 còn bị giữ ở doExecute() — test này fail (timeout)
 * đúng ở assertion đó nếu quay lại thiết kế cũ.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class JobDispatchKafkaConcurrencyIT {

  private static final String TOPIC = "job-dispatch-concurrency-it";
  private static final String GROUP_ID = "job-dispatch-concurrency-it-group";

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.0");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("messaging.provider", () -> "kafka");
    registry.add("app.job.kafka.topic", () -> TOPIC);
    registry.add("spring.kafka.consumer.group-id", () -> GROUP_ID);
    // Poller không cần cho test này -- đặt dài để chắc chắn không phải bên dispatch job.
    registry.add("app.job.fallback-poller.interval", () -> "5m");
    // >= 2 để job1 (đang chặn trên latch) không chiếm hết pool, job2 vẫn có thread riêng.
    registry.add("app.job.execution.pool-size", () -> 4);
  }

  @Autowired private KafkaJobEventPublisher jobEventPublisher;
  @Autowired private JobRepository jobRepository;
  @Autowired private CountDownLatch jobGate;

  @BeforeEach
  void cleanJobsTable() {
    jobRepository.deleteAll();
  }

  @Test
  void should_dispatchSecondJob_while_firstJobStillProcessingOnExecutor() throws Exception {
    UUID job1 = seedPendingJob();
    UUID job2 = seedPendingJob();

    jobEventPublisher.publish(new JobMessage(job1, JobType.IMPORT_PRODUCT));

    // job1 phải vào PROCESSING (CAS xong, ack đã/sắp xảy ra) -- doExecute() của nó đang chặn
    // trên jobGate, chưa hoàn tất.
    pollUntilStatus(job1, JobStatus.PROCESSING, Duration.ofSeconds(10));

    // Publish message2 TRONG LÚC job1 còn PROCESSING. Nếu listener thread bị block bởi
    // doExecute() của job1 (thiết kế cũ), job2 sẽ không bao giờ được poll/dispatch và dòng
    // dưới sẽ timeout.
    jobEventPublisher.publish(new JobMessage(job2, JobType.IMPORT_PRODUCT));
    pollUntilStatus(job2, JobStatus.PROCESSING, Duration.ofSeconds(10));

    jobGate.countDown();

    pollUntilStatus(job1, JobStatus.DONE, Duration.ofSeconds(10));
    pollUntilStatus(job2, JobStatus.DONE, Duration.ofSeconds(10));
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

  @TestConfiguration
  static class GatedProcessorConfig {

    @Bean
    CountDownLatch jobGate() {
      return new CountDownLatch(1);
    }

    @Bean
    AbstractJobProcessor gatedImportProductProcessor(
        JobStatusService jobStatusService,
        TaskScheduler heartbeatScheduler,
        Executor jobExecutionExecutor,
        CountDownLatch jobGate) {
      // Dùng thẳng jobExecutionExecutor thật (bean production, pool-size cấu hình qua
      // app.job.execution.pool-size) -- khác các IT khác đang dùng Runnable::run, vì đây
      // chính là cơ chế đang được kiểm chứng.
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
            boolean released = jobGate.await(30, TimeUnit.SECONDS);
            if (!released) {
              throw new IllegalStateException("jobGate không được release trong 30s -- lỗi test");
            }
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
