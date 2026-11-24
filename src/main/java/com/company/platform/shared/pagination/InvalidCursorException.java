package com.company.platform.shared.pagination;

public class InvalidCursorException extends RuntimeException {

  public InvalidCursorException() {
    super("Cursor phân trang không hợp lệ");
  }
}
