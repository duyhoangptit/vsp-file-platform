package com.company.platform.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobLimitExceededException;
import com.company.platform.job.domain.JobRateLimitExceededException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.messaging.JobEventPublisher;
import com.company.platform.job.messaging.JobMessage;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class JobSubmissionServiceIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void overrideLimitsForTest(DynamicPropertyRegistry registry) {
    // Ngưỡng nhỏ để dễ trigger vượt giới hạn trong test mà không cần seed nhiều dữ liệu.
    registry.add("app.job.max-active-per-user", () -> 2);
    registry.add("app.job.rate-limit.EXPORT_USER_CSV.capacity", () -> 1);
    registry.add("app.job.rate-limit.EXPORT_USER_CSV.refill-tokens", () -> 1);
    registry.add("app.job.rate-limit.EXPORT_USER_CSV.refill-period", () -> "1m");
  }

  @Autowired private JobSubmissionService jobSubmissionService;
  @Autowired private JobRepository jobRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private LinkedBlockingQueue<JobMessage> publishedMessages;

  private JsonNode samplePayload;

  @BeforeEach
  void setUp() {
    jobRepository.deleteAll();
    publishedMessages.clear();
    samplePayload = objectMapper.createObjectNode().put("filter", "active");
  }

  @Test
  void should_createJobAndPublishMessage_when_withinAllLimits() throws InterruptedException {
    UUID userId = UUID.randomUUID();

    Job job = jobSubmissionService.submit(JobType.IMPORT_PRODUCT, samplePayload, null, userId);

    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(jobRepository.findById(job.getId())).isPresent();
    JobMessage published = publishedMessages.poll(5, TimeUnit.SECONDS);
    assertThat(published).isNotNull();
    assertThat(published.jobId()).isEqualTo(job.getId());
    assertThat(published.jobType()).isEqualTo(JobType.IMPORT_PRODUCT);
  }

  @Test
  void should_throwJobLimitExceededException_when_userExceedsMaxActiveJobs() {
    UUID userId = UUID.randomUUID();
    jobSubmissionService.submit(JobType.IMPORT_PRODUCT, samplePayload, null, userId);
    jobSubmissionService.submit(JobType.IMPORT_PRODUCT, samplePayload, null, userId);
    // app.job.max-active-per-user override = 2 -> đã đủ ngưỡng ở trên.

    assertThatThrownBy(
            () -> jobSubmissionService.submit(JobType.IMPORT_PRODUCT, samplePayload, null, userId))
        .isInstanceOf(JobLimitExceededException.class);
  }

  @Test
  void should_throwJobRateLimitExceededException_when_rateLimitExceeded() {
    UUID userId = UUID.randomUUID();
    // app.job.rate-limit.EXPORT_USER_CSV.capacity override = 1 -> job đầu tiêu hết token.
    jobSubmissionService.submit(JobType.EXPORT_USER_CSV, samplePayload, null, userId);

    assertThatThrownBy(
            () -> jobSubmissionService.submit(JobType.EXPORT_USER_CSV, samplePayload, null, userId))
        .isInstanceOf(JobRateLimitExceededException.class);
  }

  @Test
  void should_returnExistingJob_when_idempotencyKeyAlreadyExists() throws InterruptedException {
    UUID userId = UUID.randomUUID();
    String idempotencyKey = "idem-" + UUID.randomUUID();

    Job first =
        jobSubmissionService.submit(JobType.IMPORT_PRODUCT, samplePayload, idempotencyKey, userId);
    assertThat(publishedMessages.poll(5, TimeUnit.SECONDS)).isNotNull();

    Job second =
        jobSubmissionService.submit(JobType.IMPORT_PRODUCT, samplePayload, idempotencyKey, userId);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(jobRepository.count()).isEqualTo(1);
    assertThat(publishedMessages.poll(1, TimeUnit.SECONDS))
        .as("replay theo idempotencyKey không được publish lại")
        .isNull();
  }

  @TestConfiguration
  static class FakeJobEventPublisherConfig {

    @Bean
    LinkedBlockingQueue<JobMessage> publishedMessages() {
      return new LinkedBlockingQueue<>();
    }

    @Bean
    JobEventPublisher fakeJobEventPublisher(LinkedBlockingQueue<JobMessage> publishedMessages) {
      return publishedMessages::add;
    }
  }
}
