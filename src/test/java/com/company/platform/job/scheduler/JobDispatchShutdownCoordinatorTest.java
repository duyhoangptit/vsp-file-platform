package com.company.platform.job.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

class JobDispatchShutdownCoordinatorTest {

  private final JobFallbackPoller jobFallbackPoller = mock(JobFallbackPoller.class);
  private final ExecutorService jobExecutionExecutor = mock(ExecutorService.class);
  private final KafkaListenerEndpointRegistry kafkaRegistry =
      mock(KafkaListenerEndpointRegistry.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<KafkaListenerEndpointRegistry> kafkaRegistryProvider =
      mock(ObjectProvider.class);

  private final JobDispatchShutdownCoordinator coordinator =
      new JobDispatchShutdownCoordinator(
          kafkaRegistryProvider, jobFallbackPoller, jobExecutionExecutor, 20L);

  @Test
  void should_pauseFallbackPollerThenStopKafkaThenDrainExecutor_inOrder_when_stopping()
      throws Exception {
    stubIfAvailableToInvokeConsumer();
    when(jobExecutionExecutor.awaitTermination(20L, TimeUnit.SECONDS)).thenReturn(true);

    coordinator.stop();

    InOrder inOrder = Mockito.inOrder(jobFallbackPoller, kafkaRegistry, jobExecutionExecutor);
    inOrder.verify(jobFallbackPoller).pause();
    inOrder.verify(kafkaRegistry).stop();
    inOrder.verify(jobExecutionExecutor).shutdown();
    inOrder.verify(jobExecutionExecutor).awaitTermination(20L, TimeUnit.SECONDS);
  }

  @Test
  void should_notCallShutdownNow_when_executorTerminatesWithinTimeout() throws Exception {
    stubIfAvailableToInvokeConsumer();
    when(jobExecutionExecutor.awaitTermination(20L, TimeUnit.SECONDS)).thenReturn(true);

    coordinator.stop();

    verify(jobExecutionExecutor, never()).shutdownNow();
  }

  @Test
  void should_callShutdownNow_when_executorDoesNotTerminateWithinTimeout() throws Exception {
    stubIfAvailableToInvokeConsumer();
    when(jobExecutionExecutor.awaitTermination(20L, TimeUnit.SECONDS)).thenReturn(false);

    coordinator.stop();

    verify(jobExecutionExecutor).shutdownNow();
  }

  @Test
  void should_callShutdownNowAndRestoreInterruptFlag_when_awaitTerminationInterrupted()
      throws Exception {
    stubIfAvailableToInvokeConsumer();
    doThrow(new InterruptedException())
        .when(jobExecutionExecutor)
        .awaitTermination(20L, TimeUnit.SECONDS);

    coordinator.stop();

    try {
      verify(jobExecutionExecutor).shutdownNow();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      // dọn cờ interrupt để không ảnh hưởng các test khác chạy cùng thread JUnit.
      Thread.interrupted();
    }
  }

  @Test
  void should_beRunning_afterStart_and_notRunning_afterStop() throws Exception {
    stubIfAvailableToInvokeConsumer();
    when(jobExecutionExecutor.awaitTermination(20L, TimeUnit.SECONDS)).thenReturn(true);

    coordinator.start();
    assertThat(coordinator.isRunning()).isTrue();

    coordinator.stop();
    assertThat(coordinator.isRunning()).isFalse();
  }

  @SuppressWarnings("unchecked")
  private void stubIfAvailableToInvokeConsumer() {
    Mockito.doAnswer(
            invocation -> {
              Consumer<KafkaListenerEndpointRegistry> consumer = invocation.getArgument(0);
              consumer.accept(kafkaRegistry);
              return null;
            })
        .when(kafkaRegistryProvider)
        .ifAvailable(any(Consumer.class));
  }
}
