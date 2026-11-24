package com.company.platform.shared.pagination;

import java.time.Instant;

/**
 * Khoảng thời gian lưu/so sánh được với cột {@code TIMESTAMP}: Java {@code Instant} nhận cả năm
 * +300000 nhưng Postgres thì không (lỗi "timestamp out of range" → 500). Chặn ở biên API.
 */
final class StorableInstants {

  static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z");
  static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

  private StorableInstants() {}

  static boolean isStorable(Instant instant) {
    return !instant.isBefore(MIN) && !instant.isAfter(MAX);
  }
}
