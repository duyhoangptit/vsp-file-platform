package com.company.platform.job.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.platform.job.domain.JobType;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobProcessorFactoryTest {

  @Test
  void should_returnRegisteredProcessor_when_jobTypeSupported() {
    JobProcessor exportProcessor = fixedProcessor(JobType.EXPORT_USER_CSV);
    JobProcessor importProcessor = fixedProcessor(JobType.IMPORT_PRODUCT);
    JobProcessorFactory factory =
        new JobProcessorFactory(List.of(exportProcessor, importProcessor));

    assertThat(factory.getProcessor(JobType.EXPORT_USER_CSV)).isSameAs(exportProcessor);
    assertThat(factory.getProcessor(JobType.IMPORT_PRODUCT)).isSameAs(importProcessor);
  }

  @Test
  void should_throwNoJobProcessorFoundException_when_jobTypeNotRegistered() {
    JobProcessorFactory factory =
        new JobProcessorFactory(List.of(fixedProcessor(JobType.EXPORT_USER_CSV)));

    assertThatThrownBy(() -> factory.getProcessor(JobType.IMPORT_PRODUCT))
        .isInstanceOf(NoJobProcessorFoundException.class);
  }

  private JobProcessor fixedProcessor(JobType jobType) {
    JobProcessor processor = mock(JobProcessor.class);
    when(processor.supports()).thenReturn(jobType);
    return processor;
  }
}
