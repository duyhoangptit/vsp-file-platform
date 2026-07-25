package com.company.platform.shared.ratelimit;

import java.time.Duration;

public interface RateLimiterService {

  /**
   * Thử tiêu 1 token khỏi bucket ứng với {@code key}. Bucket tự tạo với đầy {@code capacity} token
   * nếu chưa tồn tại, refill {@code refillTokens} token mỗi {@code refillPeriod}.
   *
   * @return true nếu còn token (đã tiêu 1), false nếu bucket rỗng.
   */
  boolean tryConsume(String key, long capacity, long refillTokens, Duration refillPeriod);
}
