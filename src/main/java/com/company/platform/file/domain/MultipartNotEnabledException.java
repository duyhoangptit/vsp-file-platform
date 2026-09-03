package com.company.platform.file.domain;

public class MultipartNotEnabledException extends RuntimeException {

  public MultipartNotEnabledException(String categoryCode) {
    super(
        "File category %s không bật multipart upload (thiếu multipart_part_size_bytes)"
            .formatted(categoryCode));
  }
}
