package com.company.platform.shared.cleanup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Kích hoạt {@link CleanupService#runCleanup()} theo lịch (mục 10 design doc). Dùng
 * SchedulingConfigurer thay vì {@code @Scheduled(fixedDelayString=...)} vì lý do tương tự
 * JobFallbackPoller: attribute string của {@code @Scheduled} không hiểu cú pháp "15m"/"30d" mà Boot
 * dùng cho phần còn lại của app.*, nên đăng ký thẳng Duration đã bind sẵn từ properties.
 *
 * <p>Cố tình tách khỏi CleanupService (bean khác) thay vì tự gọi chính nó qua method reference
 * {@code this::...} — nếu runCleanup() nằm ngay trong class này và được gọi qua self-invocation,
 * proxy {@code @Transactional} của Spring sẽ không áp dụng (cùng lý do JobScheduler đã ghi chú).
 */
@Component
@EnableConfigurationProperties(CleanupProperties.class)
public class CleanupScheduler implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

  private final CleanupService cleanupService;
  private final CleanupProperties properties;

  public CleanupScheduler(CleanupService cleanupService, CleanupProperties properties) {
    this.cleanupService = cleanupService;
    this.properties = properties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(this::runCleanupSafely, properties.interval());
  }

  void runCleanupSafely() {
    try {
      cleanupService.runCleanup();
    } catch (RuntimeException e) {
      log.warn("Cleanup tick lỗi, sẽ thử lại ở tick kế tiếp: {}", e.getMessage(), e);
    }
  }
}
