package com.company.platform.file.domain;

public class FileCategoryNotFoundException extends RuntimeException {

  public FileCategoryNotFoundException(String categoryCode) {
    super("Không tìm thấy file category: " + categoryCode);
  }
}
