package com.company.platform.job.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class JobQueueMetricsTest {

  private final JobRepository jobRepository = mock(JobRepository.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  void should_registerQueueDepthGauge_reflectingGlobalPendingCount() {
    when(jobRepository.countByStatus(JobStatus.PENDING)).thenReturn(7L);

    new JobQueueMetrics(jobRepository, meterRegistry);

    assertThat(meterRegistry.get("job.queue.depth").gauge().value()).isEqualTo(7.0);
  }

  @Test
  void should_registerPendingAndProcessingGaugesPerJobType() {
    when(jobRepository.countByStatusAndJobType(eq(JobStatus.PENDING), eq(JobType.EXPORT_USER_CSV)))
        .thenReturn(3L);
    when(jobRepository.countByStatusAndJobType(
            eq(JobStatus.PROCESSING), eq(JobType.EXPORT_USER_CSV)))
        .thenReturn(2L);
    when(jobRepository.countByStatusAndJobType(eq(JobStatus.PENDING), eq(JobType.IMPORT_USER)))
        .thenReturn(0L);
    when(jobRepository.countByStatusAndJobType(eq(JobStatus.PROCESSING), eq(JobType.IMPORT_USER)))
        .thenReturn(0L);

    new JobQueueMetrics(jobRepository, meterRegistry);

    assertThat(
            meterRegistry
                .get("job.pending.count")
                .tag("job_type", "EXPORT_USER_CSV")
                .gauge()
                .value())
        .isEqualTo(3.0);
    assertThat(
            meterRegistry
                .get("job.processing.count")
                .tag("job_type", "EXPORT_USER_CSV")
                .gauge()
                .value())
        .isEqualTo(2.0);
    assertThat(
            meterRegistry.get("job.pending.count").tag("job_type", "IMPORT_USER").gauge().value())
        .isEqualTo(0.0);
  }
}
