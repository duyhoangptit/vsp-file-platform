package com.company.platform.file.domain;

import java.util.UUID;

public class FileVerificationFailedException extends RuntimeException {

  public FileVerificationFailedException(UUID fileAssetId, String reason) {
    super("Xác thực file asset %s thất bại: %s".formatted(fileAssetId, reason));
  }
}
