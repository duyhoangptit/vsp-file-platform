package com.company.platform.job.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.job.domain.JobType;
import com.company.platform.job.messaging.JobEventConsumer;
import com.company.platform.job.messaging.JobMessage;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class KafkaJobEventPublisherConsumerIT {

  private static final String TOPIC = "job-events-it";
  private static final String GROUP_ID = "job-events-it-group";

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.0");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("messaging.provider", () -> "kafka");
    registry.add("app.job.kafka.topic", () -> TOPIC);
    registry.add("spring.kafka.consumer.group-id", () -> GROUP_ID);
  }

  @Autowired private KafkaJobEventPublisher jobEventPublisher;
  @Autowired private LinkedBlockingQueue<JobMessage> receivedMessages;

  private KafkaConsumer<String, String> rawConsumer;

  @BeforeEach
  void clearQueueAndCreateRawConsumer() {
    receivedMessages.clear();
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "raw-verification-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    rawConsumer = new KafkaConsumer<>(props);
    rawConsumer.subscribe(List.of(TOPIC));
  }

  @AfterEach
  void closeRawConsumer() {
    rawConsumer.close();
  }

  @Test
  void should_publishWithJobIdAsMessageKey_when_publishCalled() {
    UUID jobId = UUID.randomUUID();
    JobMessage message = new JobMessage(jobId, JobType.EXPORT_USER_CSV);

    jobEventPublisher.publish(message);

    // Topic dùng chung cho cả class test nên có thể còn record cũ của test khác -> tìm đúng
    // record theo key thay vì lấy record đầu tiên poll được.
    ConsumerRecord<String, String> record = pollRecordWithKey(jobId.toString());
    assertThat(record.value()).contains(jobId.toString());
  }

  @Test
  void should_invokeRegisteredConsumerAndAcknowledge_when_kafkaListenerReceivesMessage()
      throws Exception {
    UUID jobId = UUID.randomUUID();
    JobMessage message = new JobMessage(jobId, JobType.IMPORT_PRODUCT);

    jobEventPublisher.publish(message);

    assertThat(pollUntilReceived(message, Duration.ofSeconds(10)))
        .as("consumer phải nhận đúng message vừa publish")
        .isTrue();
    assertThat(pollUntilReceived(message, Duration.ofSeconds(2)))
        .as("message đã acknowledge() không được redeliver")
        .isFalse();
  }

  private ConsumerRecord<String, String> pollRecordWithKey(String expectedKey) {
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
    while (System.currentTimeMillis() < deadline) {
      ConsumerRecords<String, String> records = rawConsumer.poll(Duration.ofMillis(500));
      for (ConsumerRecord<String, String> record : records) {
        if (expectedKey.equals(record.key())) {
          return record;
        }
      }
    }
    throw new AssertionError(
        "Không nhận được record với key " + expectedKey + " từ topic " + TOPIC + " trong 10s");
  }

  private boolean pollUntilReceived(JobMessage expected, Duration window)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + window.toMillis();
    while (System.currentTimeMillis() < deadline) {
      long remainingMillis = deadline - System.currentTimeMillis();
      if (remainingMillis <= 0) {
        break;
      }
      JobMessage next =
          receivedMessages.poll(Math.min(remainingMillis, 500), TimeUnit.MILLISECONDS);
      if (expected.equals(next)) {
        return true;
      }
    }
    return false;
  }

  @TestConfiguration
  static class FakeJobEventConsumerConfig {

    @Bean
    LinkedBlockingQueue<JobMessage> receivedMessages() {
      return new LinkedBlockingQueue<>();
    }

    @Bean
    JobEventConsumer fakeJobEventConsumer(LinkedBlockingQueue<JobMessage> receivedMessages) {
      return (message, acknowledgement) -> {
        receivedMessages.add(message);
        acknowledgement.acknowledge();
      };
    }
  }
}
