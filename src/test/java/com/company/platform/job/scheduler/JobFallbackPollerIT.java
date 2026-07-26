package com.company.platform.job.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.processor.AbstractJobProcessor;
import com.company.platform.job.processor.JobContext;
import com.company.platform.job.processor.JobResult;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

/**
 * Chứng minh riêng fallback poller (mục 6.2) tự đưa job PENDING tới DONE khi KHÔNG có message nào
 * được publish. Cố tình KHÔNG dùng KafkaContainer và để messaging.provider unset — nhờ vậy
 * KafkaJobEventConsumer (@ConditionalOnProperty) không load trong context này, chứng minh bằng cấu
 * trúc (không có hạ tầng Kafka nào tồn tại) thay vì chỉ dựa vào canh thời gian.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class JobFallbackPollerIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.job.fallback-poller.interval", () -> "1s");
  }

  @Autowired private JobRepository jobRepository;

  @BeforeEach
  void cleanJobsTable() {
    jobRepository.deleteAll();
  }

  @Test
  void should_driveJobToDone_when_noMessagePublishedAndOnlyPollerRuns()
      throws InterruptedException {
    UUID jobId = UUID.randomUUID();
    jobRepository.save(
        new Job(
            jobId, JobType.IMPORT_PRODUCT, Map.of("k", "v"), null, null, null, UUID.randomUUID()));

    // Không publish message, không gọi thủ công JobScheduler — chỉ có fallback poller chạy nền.
    Job done = pollUntilDone(jobId, Duration.ofSeconds(15));
    assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(done.getResultLocation()).isEqualTo("bucket/fake-result.csv");
  }

  private Job pollUntilDone(UUID jobId, Duration window) throws InterruptedException {
    long deadline = System.currentTimeMillis() + window.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Optional<Job> job = jobRepository.findById(jobId);
      if (job.isPresent() && job.get().getStatus() == JobStatus.DONE) {
        return job.get();
      }
      Thread.sleep(200);
    }
    throw new AssertionError("Job " + jobId + " không đạt DONE trong " + window);
  }

  @TestConfiguration
  static class FakeExportProcessorConfig {

    @Bean
    AbstractJobProcessor fakeExportUserCsvProcessor(
        JobStatusService jobStatusService, TaskScheduler heartbeatScheduler) {
      // Runnable::run -- test này verify fallback poller tới DONE, không phải cơ chế executor
      // riêng.
      return new AbstractJobProcessor(jobStatusService, heartbeatScheduler, Runnable::run) {
        @Override
        public JobType supports() {
          return JobType.IMPORT_PRODUCT;
        }

        @Override
        protected void validate(Map<String, Object> payload) {}

        @Override
        protected JobResult doExecute(JobContext context) {
          return new JobResult("bucket/fake-result.csv", 1);
        }
      };
    }
  }
}
