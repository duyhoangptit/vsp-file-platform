package com.company.platform.job.messaging.kafka;

import com.company.platform.job.messaging.Acknowledgement;
import org.springframework.kafka.support.Acknowledgment;

class KafkaAcknowledgement implements Acknowledgement {

  private final Acknowledgment kafkaAck;

  KafkaAcknowledgement(Acknowledgment kafkaAck) {
    this.kafkaAck = kafkaAck;
  }

  @Override
  public void acknowledge() {
    kafkaAck.acknowledge();
  }

  @Override
  public void reject() {
    // Không ack -> Kafka tự redeliver ở lần poll sau. An toàn vì message chỉ là
    // wake-up signal, worker luôn re-check CAS status trong DB.
  }
}
