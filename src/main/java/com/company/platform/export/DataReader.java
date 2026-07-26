package com.company.platform.export;

import org.springframework.data.domain.Page;

/**
 * Keyset pagination (mục 6.4 design doc): trả 1 trang dữ liệu sau con trỏ {@code cursor}, trang
 * rỗng nghĩa là đã đọc hết. Không dùng OFFSET, không load toàn bộ tập kết quả vào bộ nhớ.
 */
public interface DataReader<T> {

  Page<T> readNextPage(String cursor, int pageSize);
}
