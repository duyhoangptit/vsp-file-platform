package com.company.platform.job.processor;

import com.company.platform.job.domain.JobType;

public interface JobProcessor {

  JobType supports();

  void process(JobContext context);
}
