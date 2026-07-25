package com.company.platform.job.messaging.kafka;

import com.company.platform.job.messaging.JobEventPublisher;
import com.company.platform.job.messaging.JobMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "messaging", name = "provider", havingValue = "kafka")
public class KafkaJobEventPublisher implements JobEventPublisher {

  private final KafkaTemplate<String, JobMessage> kafkaTemplate;
  private final String topic;

  public KafkaJobEventPublisher(
      KafkaTemplate<String, JobMessage> kafkaTemplate,
      @Value("${app.job.kafka.topic}") String topic) {
    this.kafkaTemplate = kafkaTemplate;
    this.topic = topic;
  }

  @Override
  public void publish(JobMessage message) {
    kafkaTemplate.send(topic, message.jobId().toString(), message);
  }
}
