package com.company.platform.job.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.job.domain.JobType;
import com.company.platform.job.messaging.Acknowledgement;
import com.company.platform.job.messaging.JobMessage;
import com.company.platform.job.processor.NoJobProcessorFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobDispatchConsumerTest {

  private final JobScheduler jobScheduler = mock(JobScheduler.class);
  private final Acknowledgement acknowledgement = mock(Acknowledgement.class);
  private final JobDispatchConsumer consumer = new JobDispatchConsumer(jobScheduler);

  @Test
  void should_acknowledge_when_pollAndDispatchSucceeds() {
    when(jobScheduler.pollAndDispatch()).thenReturn(true);

    consumer.onMessage(message(), acknowledgement);

    verify(acknowledgement).acknowledge();
    verify(acknowledgement, never()).reject();
  }

  @Test
  void should_acknowledge_when_noPendingJobFound() {
    when(jobScheduler.pollAndDispatch()).thenReturn(false);

    consumer.onMessage(message(), acknowledgement);

    verify(acknowledgement).acknowledge();
    verify(acknowledgement, never()).reject();
  }

  @Test
  void should_rejectAndNotPropagate_when_pollAndDispatchThrowsNoJobProcessorFoundException() {
    doThrow(new NoJobProcessorFoundException(JobType.EXPORT_USER_CSV))
        .when(jobScheduler)
        .pollAndDispatch();

    consumer.onMessage(message(), acknowledgement);

    verify(acknowledgement).reject();
    verify(acknowledgement, never()).acknowledge();
  }

  @Test
  void should_rejectAndNotPropagate_when_pollAndDispatchThrowsGenericRuntimeException() {
    doThrow(new RuntimeException("lỗi DB tạm thời")).when(jobScheduler).pollAndDispatch();

    consumer.onMessage(message(), acknowledgement);

    verify(acknowledgement).reject();
    verify(acknowledgement, never()).acknowledge();
  }

  private JobMessage message() {
    return new JobMessage(UUID.randomUUID(), JobType.EXPORT_USER_CSV);
  }
}
