package com.company.platform.job.scheduler;

import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Lưới an toàn chung cho Kafka lẫn Redis (mục 6.2 design doc): quét PENDING job đã "già" hơn
 * interval cấu hình, tự trigger JobScheduler xử lý — phòng trường hợp message bị mất (đặc biệt
 * Redis Pub/Sub không persist).
 *
 * <p>Dùng SchedulingConfigurer thay vì @Scheduled(fixedDelayString=...) vì attribute dạng string
 * của @Scheduled chỉ hiểu millis thuần hoặc ISO-8601 nghiêm ngặt (PT30S), không hiểu cú pháp
 * "30s"/"1m" mà Boot dùng cho phần còn lại của app.job.* (VD refill-period: 1m). Đăng ký thẳng
 * Duration đã bind sẵn từ properties để application.yml giữ nguyên style nhất quán.
 */
@Component
@EnableConfigurationProperties(JobFallbackPollerProperties.class)
public class JobFallbackPoller implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(JobFallbackPoller.class);

  private final JobRepository jobRepository;
  private final JobScheduler jobScheduler;
  private final JobFallbackPollerProperties properties;
  private final AtomicBoolean paused = new AtomicBoolean(false);

  public JobFallbackPoller(
      JobRepository jobRepository,
      JobScheduler jobScheduler,
      JobFallbackPollerProperties properties) {
    this.jobRepository = jobRepository;
    this.jobScheduler = jobScheduler;
    this.properties = properties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(this::pollIfStale, properties.interval());
  }

  // Gọi khi app đang graceful shutdown (JobDispatchShutdownCoordinator) -- tick sau đó tự bỏ
  // qua, tránh CAS 1 job sang PROCESSING rồi bị executor đã shutdown() từ chối nhận việc,
  // khiến job kẹt PROCESSING vĩnh viễn (chưa có reconciler heartbeat-timeout để tự phục hồi).
  void pause() {
    paused.set(true);
  }

  // fixedDelay (tick kế tiếp tính từ lúc tick trước KẾT THÚC), tối đa dispatch 1 job/tick —
  // pollAndDispatch() chỉ block trong thời gian thử CAS (nhanh), phần doExecute() thật chạy
  // trên jobExecutionExecutor riêng (AbstractJobProcessor) nên tick này không bị giữ lâu.
  void pollIfStale() {
    if (paused.get()) {
      return;
    }
    try {
      Instant threshold = Instant.now().minus(properties.interval());
      if (jobRepository.existsByStatusAndCreatedAtBefore(JobStatus.PENDING, threshold)) {
        jobScheduler.pollAndDispatch();
      }
    } catch (RuntimeException e) {
      log.warn("Fallback poller tick lỗi, sẽ thử lại ở tick kế tiếp: {}", e.getMessage(), e);
    }
  }
}
