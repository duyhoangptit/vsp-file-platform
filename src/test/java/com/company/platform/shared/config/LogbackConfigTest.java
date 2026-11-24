package com.company.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/**
 * logback-spring.xml: profile local/dev → text; còn lại (container) → JSON logstash có field {@code
 * level} để Alloy gắn label khi đẩy lên Loki. Không dùng logstash-logback-encoder.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogbackConfigTest {

  private LoggingSystem loggingSystem;

  private void initWithProfiles(String... profiles) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles(profiles);
    loggingSystem = LoggingSystem.get(getClass().getClassLoader());
    loggingSystem.cleanUp();
    loggingSystem.beforeInitialize();
    loggingSystem.initialize(
        new LoggingInitializationContext(env), "classpath:logback-spring.xml", null);
  }

  @AfterEach
  void restore() {
    // trả logging về dạng text cho các test chạy sau trong cùng JVM
    initWithProfiles("dev");
  }

  @Test
  void should_logLogstashJsonWithLevel_when_noLocalProfile(CapturedOutput output) {
    initWithProfiles();
    LoggerFactory.getLogger("com.company.platform.Probe").info("json-probe");

    String line =
        output.getOut().lines().filter(l -> l.contains("json-probe")).findFirst().orElseThrow();
    assertThat(line).startsWith("{");
    assertThat(line)
        .contains("\"level\":\"INFO\"")
        .contains("\"logger_name\":\"com.company.platform.Probe\"")
        .contains("\"message\":\"json-probe\"")
        .contains("\"@timestamp\"");
  }

  @Test
  void should_logPlainText_when_devProfile(CapturedOutput output) {
    initWithProfiles("dev");
    LoggerFactory.getLogger("com.company.platform.Probe").info("text-probe");

    String line =
        output.getOut().lines().filter(l -> l.contains("text-probe")).findFirst().orElseThrow();
    assertThat(line).doesNotStartWith("{").contains("INFO");
  }

  @Test
  void should_logPlainText_when_localProfile(CapturedOutput output) {
    initWithProfiles("local");
    LoggerFactory.getLogger("com.company.platform.Probe").info("local-probe");

    assertThat(
            output
                .getOut()
                .lines()
                .filter(l -> l.contains("local-probe"))
                .findFirst()
                .orElseThrow())
        .doesNotStartWith("{");
  }

  @Test
  void should_hideInfoFromNoisyLibraries_when_logging(CapturedOutput output) {
    initWithProfiles();
    LoggerFactory.getLogger("org.apache.kafka.clients.Probe").info("kafka-info-probe");
    LoggerFactory.getLogger("software.amazon.awssdk.Probe").info("aws-info-probe");
    LoggerFactory.getLogger("org.apache.kafka.clients.Probe").warn("kafka-warn-probe");

    assertThat(output.getOut())
        .doesNotContain("kafka-info-probe")
        .doesNotContain("aws-info-probe")
        .contains("kafka-warn-probe");
  }
}
