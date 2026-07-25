package com.company.platform.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Không dùng @SpringBootTest: RedisTokenBucketRateLimiterService chỉ phụ thuộc
// StringRedisTemplate, không cần bootstrap cả app context (tránh phải kèm Postgres container chỉ
// để thoả JPA/Liquibase khởi động, vốn không liên quan gì tới test này).
@Testcontainers
class RedisTokenBucketRateLimiterServiceIT {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static RateLimiterService rateLimiterService;

  @BeforeAll
  static void setUpRedisClient() {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    rateLimiterService = new RedisTokenBucketRateLimiterService(redisTemplate);
  }

  @AfterAll
  static void tearDownRedisClient() {
    connectionFactory.destroy();
  }

  private String uniqueKey() {
    return "test:" + UUID.randomUUID();
  }

  @Test
  void should_allowUpToCapacity_when_bucketFull() {
    String key = uniqueKey();

    assertThat(rateLimiterService.tryConsume(key, 3, 3, Duration.ofMinutes(1))).isTrue();
    assertThat(rateLimiterService.tryConsume(key, 3, 3, Duration.ofMinutes(1))).isTrue();
    assertThat(rateLimiterService.tryConsume(key, 3, 3, Duration.ofMinutes(1))).isTrue();
    assertThat(rateLimiterService.tryConsume(key, 3, 3, Duration.ofMinutes(1))).isFalse();
  }

  @Test
  void should_refillTokens_when_refillPeriodElapsed() throws InterruptedException {
    String key = uniqueKey();
    Duration refillPeriod = Duration.ofMillis(200);

    assertThat(rateLimiterService.tryConsume(key, 1, 1, refillPeriod)).isTrue();
    assertThat(rateLimiterService.tryConsume(key, 1, 1, refillPeriod)).isFalse();

    Thread.sleep(250);

    assertThat(rateLimiterService.tryConsume(key, 1, 1, refillPeriod)).isTrue();
  }
}
