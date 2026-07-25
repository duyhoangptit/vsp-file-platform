package com.company.platform.job.messaging;

public interface JobEventPublisher {

  void publish(JobMessage message);
}
