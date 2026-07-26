package com.company.platform.job.scheduler;

import com.company.platform.job.messaging.Acknowledgement;
import com.company.platform.job.messaging.JobEventConsumer;
import com.company.platform.job.messaging.JobMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Broker-agnostic wake-up handler (mục 6.2/6.3 design doc): message chỉ báo "có việc để làm", không
 * nhất thiết đúng jobId trong message được xử lý — JobScheduler tự chọn job ưu tiên cao nhất trong
 * DB. Không import package Kafka/Redis nên hoạt động y hệt khi thêm adapter mới.
 */
@Component
public class JobDispatchConsumer implements JobEventConsumer {

  private static final Logger log = LoggerFactory.getLogger(JobDispatchConsumer.class);

  private final JobScheduler jobScheduler;

  public JobDispatchConsumer(JobScheduler jobScheduler) {
    this.jobScheduler = jobScheduler;
  }

  @Override
  public void onMessage(JobMessage message, Acknowledgement acknowledgement) {
    try {
      jobScheduler.pollAndDispatch();
      acknowledgement.acknowledge();
    } catch (RuntimeException e) {
      log.warn(
          "Không dispatch được sau wake-up signal (job {} trong message): {}",
          message.jobId(),
          e.getMessage(),
          e);
      acknowledgement.reject();
    }
  }
}
