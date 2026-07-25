package com.company.platform.job.messaging;

public interface Acknowledgement {

  void acknowledge();

  void reject();
}
