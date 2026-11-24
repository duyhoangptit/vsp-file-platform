package com.company.platform.shared.pagination;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Cột thời gian là {@code TIMESTAMP} (không múi giờ) và Hibernate ghi theo UTC ({@code
 * hibernate.jdbc.time_zone: UTC}). Query JDBC thuần phải bind/đọc đúng quy ước đó, KHÔNG dùng
 * {@code java.sql.Timestamp} (driver sẽ quy đổi theo TZ của JVM).
 */
public final class UtcTimes {

  private UtcTimes() {}

  public static LocalDateTime toDb(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public static Instant fromDb(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
