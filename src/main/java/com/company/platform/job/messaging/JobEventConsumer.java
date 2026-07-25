package com.company.platform.job.messaging;

public interface JobEventConsumer {

  void onMessage(JobMessage message, Acknowledgement acknowledgement);
}
