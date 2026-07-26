package com.company.platform.shared.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor pool riêng cho phần thực thi job (doExecute), tách khỏi thread gọi
 * AbstractJobProcessor.process() (Kafka listener thread / fallback poller thread) — đúng mục 6.4
 * design doc: "Số job PROCESSING đồng thời/instance -> Semaphore/executor pool riêng trong worker".
 */
@Configuration
public class JobExecutionConfig {

  @Bean(destroyMethod = "shutdown")
  public ExecutorService jobExecutionExecutor(
      @Value("${app.job.execution.pool-size:4}") int poolSize) {
    return Executors.newFixedThreadPool(poolSize, jobExecutionThreadFactory());
  }

  private ThreadFactory jobExecutionThreadFactory() {
    AtomicInteger counter = new AtomicInteger(1);
    return runnable -> new Thread(runnable, "job-exec-" + counter.getAndIncrement());
  }
}
