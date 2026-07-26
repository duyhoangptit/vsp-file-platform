package com.company.platform.job.scheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Điều phối graceful shutdown cho đường xử lý job: dừng nhận việc mới (Kafka listener + fallback
 * poller) trước, rồi mới đóng jobExecutionExecutor có chờ job đang chạy hoàn tất.
 *
 * <p>Dùng SmartLifecycle thay vì @PreDestroy: stop() ở đây chạy trong bước
 * LifecycleProcessor.onClose() của Spring, TRƯỚC khi context bắt đầu destroyBeans() (huỷ
 * DataSource/JobStatusService... mà job đang chạy trên executor còn cần) — @PreDestroy chạy lẫn
 * trong chính quá trình destroyBeans(), không tách biệt được, và không đọc
 * spring.lifecycle.timeout-per-shutdown-phase.
 *
 * <p>Không dựa vào thứ tự phase giữa bean này và KafkaListenerEndpointRegistry (cả 2 mặc định đều
 * rơi vào phase Integer.MAX_VALUE nếu không override getPhase() -- thứ tự giữa các bean CÙNG phase
 * không được Spring đảm bảo) -- thay vào đó gọi thẳng registry.stop() làm bước đầu tiên trong
 * stop() để đảm bảo thứ tự bằng code, không phụ thuộc số phase.
 */
@Component
public class JobDispatchShutdownCoordinator implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(JobDispatchShutdownCoordinator.class);

  private final ObjectProvider<KafkaListenerEndpointRegistry> kafkaRegistryProvider;
  private final JobFallbackPoller jobFallbackPoller;
  private final ExecutorService jobExecutionExecutor;
  private final long shutdownTimeoutSeconds;

  private volatile boolean running = true;

  public JobDispatchShutdownCoordinator(
      ObjectProvider<KafkaListenerEndpointRegistry> kafkaRegistryProvider,
      JobFallbackPoller jobFallbackPoller,
      ExecutorService jobExecutionExecutor,
      @Value("${app.job.execution.shutdown-timeout-seconds:20}") long shutdownTimeoutSeconds) {
    this.kafkaRegistryProvider = kafkaRegistryProvider;
    this.jobFallbackPoller = jobFallbackPoller;
    this.jobExecutionExecutor = jobExecutionExecutor;
    this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void stop() {
    jobFallbackPoller.pause();
    kafkaRegistryProvider.ifAvailable(KafkaListenerEndpointRegistry::stop);

    jobExecutionExecutor.shutdown();
    try {
      if (!jobExecutionExecutor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
        log.warn(
            "Job execution executor không kết thúc sau {}s, ép dừng (shutdownNow)",
            shutdownTimeoutSeconds);
        jobExecutionExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      jobExecutionExecutor.shutdownNow();
    } finally {
      running = false;
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }
}
