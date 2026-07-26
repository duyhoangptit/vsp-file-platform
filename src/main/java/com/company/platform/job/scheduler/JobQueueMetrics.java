package com.company.platform.job.scheduler;

import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// Gauge kéo (pull-based): đăng ký 1 lần lúc khởi tạo, Micrometer/Prometheus tự gọi lại hàm giá
// trị mỗi lần scrape /actuator/prometheus, không cần @Scheduled riêng để cập nhật. Đặt cùng
// package job.scheduler như JobFallbackPoller (component độc lập, không sửa JobScheduler).
@Component
public class JobQueueMetrics {

  public JobQueueMetrics(JobRepository jobRepository, MeterRegistry meterRegistry) {
    Gauge.builder("job.queue.depth", jobRepository, r -> r.countByStatus(JobStatus.PENDING))
        .register(meterRegistry);

    for (JobType jobType : JobType.values()) {
      Gauge.builder(
              "job.pending.count",
              jobRepository,
              r -> r.countByStatusAndJobType(JobStatus.PENDING, jobType))
          .tag("job_type", jobType.name())
          .register(meterRegistry);

      Gauge.builder(
              "job.processing.count",
              jobRepository,
              r -> r.countByStatusAndJobType(JobStatus.PROCESSING, jobType))
          .tag("job_type", jobType.name())
          .register(meterRegistry);
    }
  }
}
