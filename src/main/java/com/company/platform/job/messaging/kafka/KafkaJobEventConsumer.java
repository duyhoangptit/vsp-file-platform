package com.company.platform.job.messaging.kafka;

import com.company.platform.job.messaging.JobEventConsumer;
import com.company.platform.job.messaging.JobMessage;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "messaging", name = "provider", havingValue = "kafka")
public class KafkaJobEventConsumer {

  private final List<JobEventConsumer> consumers;

  public KafkaJobEventConsumer(List<JobEventConsumer> consumers) {
    this.consumers = consumers;
  }

  @KafkaListener(topics = "${app.job.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(ConsumerRecord<String, JobMessage> record, Acknowledgment kafkaAck) {
    if (consumers.isEmpty()) {
      kafkaAck.acknowledge();
      return;
    }
    JobMessage message = record.value();
    KafkaAcknowledgement acknowledgement = new KafkaAcknowledgement(kafkaAck);
    for (JobEventConsumer consumer : consumers) {
      consumer.onMessage(message, acknowledgement);
    }
  }
}
