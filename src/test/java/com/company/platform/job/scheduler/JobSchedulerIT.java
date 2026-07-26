package com.company.platform.job.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.processor.JobContext;
import com.company.platform.job.processor.JobProcessor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class JobSchedulerIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private JobScheduler jobScheduler;
  @Autowired private JobStatusService jobStatusService;
  @Autowired private JobRepository jobRepository;
  @Autowired private BlockingQueue<JobContext> receivedContexts;

  @BeforeEach
  void cleanJobsTableAndQueue() {
    // pollNext()/pollAndDispatch() luôn lấy job ưu tiên cao nhất trong TOÀN BẢNG, không lọc
    // theo id cụ thể -> phải dọn sạch trước mỗi test để không lẫn job PENDING còn sót lại từ
    // test khác (thứ tự chạy test của JUnit không đảm bảo theo khai báo).
    jobRepository.deleteAll();
    receivedContexts.clear();
  }

  private UUID seedPendingJob(short priority) {
    return seedPendingJob(JobType.IMPORT_PRODUCT, priority);
  }

  private UUID seedPendingJob(JobType jobType, short priority) {
    UUID jobId = UUID.randomUUID();
    jobRepository.save(
        new Job(jobId, jobType, Map.of("k", "v"), priority, null, null, UUID.randomUUID()));
    return jobId;
  }

  @Test
  void should_returnHighestPriorityJob_first_when_multipleJobsPending() {
    UUID lowId = seedPendingJob((short) 0);
    UUID urgentId = seedPendingJob((short) 2);
    UUID highId = seedPendingJob((short) 1);

    Job first = jobScheduler.pollNext().orElseThrow();
    assertThat(first.getId()).isEqualTo(urgentId);
    jobStatusService.tryMarkProcessing(first.getId());

    Job second = jobScheduler.pollNext().orElseThrow();
    assertThat(second.getId()).isEqualTo(highId);
    jobStatusService.tryMarkProcessing(second.getId());

    Job third = jobScheduler.pollNext().orElseThrow();
    assertThat(third.getId()).isEqualTo(lowId);
  }

  @Test
  void should_useCreatedAtAscAsTiebreaker_when_jobsHaveSamePriority() throws InterruptedException {
    UUID firstId = seedPendingJob((short) 1);
    Thread.sleep(50);
    seedPendingJob((short) 1);

    Job next = jobScheduler.pollNext().orElseThrow();

    assertThat(next.getId()).isEqualTo(firstId);
  }

  @Test
  void should_returnEmpty_when_noPendingJobs() {
    assertThat(jobScheduler.pollNext()).isEmpty();
  }

  @Test
  void should_dispatchToMatchingProcessor_when_pendingJobExists() throws InterruptedException {
    UUID jobId = seedPendingJob(JobType.IMPORT_PRODUCT, (short) 0);

    boolean dispatched = jobScheduler.pollAndDispatch();

    assertThat(dispatched).isTrue();
    JobContext received = receivedContexts.poll(5, TimeUnit.SECONDS);
    assertThat(received).isNotNull();
    assertThat(received.jobId()).isEqualTo(jobId);
    assertThat(received.jobType()).isEqualTo(JobType.IMPORT_PRODUCT);
  }

  @Test
  void should_returnFalse_when_noPendingJobsToDispatch() {
    assertThat(jobScheduler.pollAndDispatch()).isFalse();
  }

  @TestConfiguration
  static class FakeJobProcessorConfig {

    @Bean
    BlockingQueue<JobContext> receivedContexts() {
      return new LinkedBlockingQueue<>();
    }

    @Bean
    JobProcessor fakeExportUserCsvProcessor(BlockingQueue<JobContext> receivedContexts) {
      return new JobProcessor() {
        @Override
        public JobType supports() {
          return JobType.IMPORT_PRODUCT;
        }

        @Override
        public void process(JobContext context) {
          receivedContexts.add(context);
        }
      };
    }
  }
}
