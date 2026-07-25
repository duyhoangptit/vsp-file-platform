package com.company.platform.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisTokenBucketRateLimiterService implements RateLimiterService {

  private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/token-bucket.lua"), Long.class);

  private final StringRedisTemplate redisTemplate;

  public RedisTokenBucketRateLimiterService(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  public boolean tryConsume(String key, long capacity, long refillTokens, Duration refillPeriod) {
    Long allowed =
        redisTemplate.execute(
            TOKEN_BUCKET_SCRIPT,
            List.of(key),
            String.valueOf(capacity),
            String.valueOf(refillTokens),
            String.valueOf(refillPeriod.toMillis()),
            String.valueOf(System.currentTimeMillis()));
    return allowed != null && allowed == 1L;
  }
}
