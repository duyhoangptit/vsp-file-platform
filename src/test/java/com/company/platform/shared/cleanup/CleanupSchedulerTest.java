package com.company.platform.shared.cleanup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class CleanupSchedulerTest {

  private final CleanupService cleanupService = mock(CleanupService.class);
  private final CleanupProperties properties =
      new CleanupProperties(Duration.ofMinutes(15), 500, Duration.ofDays(30), Duration.ofHours(2));
  private final CleanupScheduler scheduler = new CleanupScheduler(cleanupService, properties);

  @Test
  void should_registerFixedDelayTaskWithConfiguredInterval_when_configureTasksCalled() {
    ScheduledTaskRegistrar registrar = mock(ScheduledTaskRegistrar.class);

    scheduler.configureTasks(registrar);

    verify(registrar).addFixedDelayTask(any(Runnable.class), eq(Duration.ofMinutes(15)));
  }

  @Test
  void should_callRunCleanupExactlyOnce_when_runCleanupSafelyInvoked() {
    scheduler.runCleanupSafely();

    verify(cleanupService, times(1)).runCleanup();
  }

  @Test
  void should_notPropagate_when_runCleanupThrows() {
    doThrow(new RuntimeException("lỗi DB tạm thời")).when(cleanupService).runCleanup();

    scheduler.runCleanupSafely();
  }
}
