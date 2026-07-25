package com.company.platform.job.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaJobEventAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
          .withUserConfiguration(KafkaJobEventPublisher.class, KafkaJobEventConsumer.class)
          .withPropertyValues("app.job.kafka.topic=test-topic");

  @Test
  void should_registerKafkaAdapterBeans_when_messagingProviderIsKafka() {
    contextRunner
        .withPropertyValues("messaging.provider=kafka")
        .run(
            context -> {
              assertThat(context).hasSingleBean(KafkaJobEventPublisher.class);
              assertThat(context).hasSingleBean(KafkaJobEventConsumer.class);
            });
  }

  @Test
  void should_notRegisterKafkaAdapterBeans_when_messagingProviderNotSet() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(KafkaJobEventPublisher.class);
          assertThat(context).doesNotHaveBean(KafkaJobEventConsumer.class);
        });
  }

  @Test
  void should_notRegisterKafkaAdapterBeans_when_messagingProviderIsRedis() {
    contextRunner
        .withPropertyValues("messaging.provider=redis")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(KafkaJobEventPublisher.class);
              assertThat(context).doesNotHaveBean(KafkaJobEventConsumer.class);
            });
  }
}
