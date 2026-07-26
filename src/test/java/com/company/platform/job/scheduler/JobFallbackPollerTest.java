package com.company.platform.job.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class JobFallbackPollerTest {

  private final JobRepository jobRepository = mock(JobRepository.class);
  private final JobScheduler jobScheduler = mock(JobScheduler.class);
  private final JobFallbackPollerProperties properties =
      new JobFallbackPollerProperties(Duration.ofSeconds(30));
  private final JobFallbackPoller poller =
      new JobFallbackPoller(jobRepository, jobScheduler, properties);

  @Test
  void should_notCallPollAndDispatch_when_noStalePendingJobsExist() {
    when(jobRepository.existsByStatusAndCreatedAtBefore(eq(JobStatus.PENDING), any(Instant.class)))
        .thenReturn(false);

    poller.pollIfStale();

    verify(jobScheduler, never()).pollAndDispatch();
  }

  @Test
  void should_callPollAndDispatchExactlyOnce_when_stalePendingJobExists() {
    when(jobRepository.existsByStatusAndCreatedAtBefore(eq(JobStatus.PENDING), any(Instant.class)))
        .thenReturn(true);

    poller.pollIfStale();

    verify(jobScheduler, times(1)).pollAndDispatch();
  }

  @Test
  void should_passThresholdComputedFromNowMinusInterval() {
    when(jobRepository.existsByStatusAndCreatedAtBefore(eq(JobStatus.PENDING), any(Instant.class)))
        .thenReturn(false);
    Instant before = Instant.now().minus(properties.interval());

    poller.pollIfStale();

    Instant after = Instant.now().minus(properties.interval());
    ArgumentCaptor<Instant> thresholdCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(jobRepository)
        .existsByStatusAndCreatedAtBefore(eq(JobStatus.PENDING), thresholdCaptor.capture());
    Instant captured = thresholdCaptor.getValue();
    assertThat(captured).isBetween(before, after);
  }

  @Test
  void should_notPropagate_when_pollAndDispatchThrows() {
    when(jobRepository.existsByStatusAndCreatedAtBefore(eq(JobStatus.PENDING), any(Instant.class)))
        .thenReturn(true);
    doThrow(new RuntimeException("lỗi khi dispatch")).when(jobScheduler).pollAndDispatch();

    poller.pollIfStale();
  }

  @Test
  void should_notPropagate_when_existsQueryThrows() {
    doThrow(new RuntimeException("lỗi kết nối DB"))
        .when(jobRepository)
        .existsByStatusAndCreatedAtBefore(eq(JobStatus.PENDING), any(Instant.class));

    poller.pollIfStale();

    verify(jobScheduler, never()).pollAndDispatch();
  }

  @Test
  void should_registerFixedDelayTaskWithConfiguredInterval_when_configureTasksCalled() {
    ScheduledTaskRegistrar registrar = mock(ScheduledTaskRegistrar.class);

    poller.configureTasks(registrar);

    verify(registrar).addFixedDelayTask(any(Runnable.class), eq(Duration.ofSeconds(30)));
  }
}
