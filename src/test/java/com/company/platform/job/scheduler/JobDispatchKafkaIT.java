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
 * Chứng minh riêng đường Kafka (JobDispatchConsumer) đủ để đưa job PENDING tới DONE — không cần đợi
 * fallback poller. Đặt fallback-poller.interval rất dài để poller không thể là nguyên nhân job hoàn
 * thành trong khoảng thời gian assert của test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class JobDispatchKafkaIT {

  private static final String TOPIC = "job-dispatch-it";
  private static final String GROUP_ID = "job-dispatch-it-group";

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.0");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("messaging.provider", () -> "kafka");
    registry.add("app.job.kafka.topic", () -> TOPIC);
    registry.add("spring.kafka.consumer.group-id", () -> GROUP_ID);
    // Đủ dài để trong cửa sổ assert (~10s) của test, fallback poller không thể là bên đưa job
    // này tới DONE — chỉ có đường Kafka (JobDispatchConsumer) mới kịp.
    registry.add("app.job.fallback-poller.interval", () -> "5m");
  }

  @Autowired private KafkaJobEventPublisher jobEventPublisher;
  @Autowired private JobRepository jobRepository;

  @BeforeEach
  void cleanJobsTable() {
    jobRepository.deleteAll();
  }

  @Test
  void should_driveJobToDone_when_kafkaMessagePublished() throws InterruptedException {
    UUID jobId = UUID.randomUUID();
    jobRepository.save(
        new Job(
            jobId, JobType.IMPORT_PRODUCT, Map.of("k", "v"), null, null, null, UUID.randomUUID()));

    jobEventPublisher.publish(new JobMessage(jobId, JobType.IMPORT_PRODUCT));

    Job done = pollUntilDone(jobId, Duration.ofSeconds(10));
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
      // Runnable::run -- test này verify dispatch qua Kafka tới DONE, không phải cơ chế
      // executor riêng (đã có JobDispatchKafkaConcurrencyIT lo việc đó).
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
