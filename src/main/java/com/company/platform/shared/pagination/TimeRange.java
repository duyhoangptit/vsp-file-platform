package com.company.platform.shared.pagination;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Khoảng thời gian [from, to) của query admin; giới hạn để GROUP BY/ILIKE không quét cả bảng. */
public record TimeRange(Instant from, Instant to) {

  public static final int DEFAULT_DAYS = 30;
  public static final int MAX_DAYS = 92;

  public static TimeRange resolve(Instant from, Instant to, Clock clock) {
    Instant resolvedTo = to != null ? to : clock.instant();
    Instant resolvedFrom = from != null ? from : resolvedTo.minus(Duration.ofDays(DEFAULT_DAYS));
    if (!resolvedFrom.isBefore(resolvedTo)) {
      throw new InvalidTimeRangeException("from phải nhỏ hơn to");
    }
    if (Duration.between(resolvedFrom, resolvedTo).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
      throw new InvalidTimeRangeException("Khoảng thời gian tối đa " + MAX_DAYS + " ngày");
    }
    return new TimeRange(resolvedFrom, resolvedTo);
  }
}
