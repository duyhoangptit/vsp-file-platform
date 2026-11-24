package com.company.platform.shared.pagination;

import java.util.List;

/** 1 trang keyset: {@code nextCursor == null} = trang cuối. */
public record PageSlice<T>(List<T> items, String nextCursor) {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  public static int clampLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    return Math.max(1, Math.min(MAX_LIMIT, limit));
  }
}
