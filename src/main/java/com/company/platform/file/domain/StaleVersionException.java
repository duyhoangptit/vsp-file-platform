package com.company.platform.file.domain;

/** Client gửi {@code version} cũ: dữ liệu đã bị người khác sửa trước đó (optimistic lock). */
public class StaleVersionException extends RuntimeException {

  public StaleVersionException(String resource, long expected, long actual) {
    super(
        "%s đã bị thay đổi (version hiện tại %d, gửi lên %d) — tải lại rồi thử lại"
            .formatted(resource, actual, expected));
  }
}
