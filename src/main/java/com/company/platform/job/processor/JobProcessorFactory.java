package com.company.platform.job.processor;

import com.company.platform.job.domain.JobType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class JobProcessorFactory {

  private final Map<JobType, JobProcessor> processorsByType;

  public JobProcessorFactory(List<JobProcessor> processors) {
    this.processorsByType =
        processors.stream().collect(Collectors.toMap(JobProcessor::supports, Function.identity()));
  }

  public JobProcessor getProcessor(JobType jobType) {
    JobProcessor processor = processorsByType.get(jobType);
    if (processor == null) {
      throw new NoJobProcessorFoundException(jobType);
    }
    return processor;
  }
}
