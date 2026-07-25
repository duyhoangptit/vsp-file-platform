package com.company.platform.file.domain;

import java.util.UUID;

public class FileAssetNotFoundException extends RuntimeException {

  public FileAssetNotFoundException(UUID fileAssetId) {
    super("Không tìm thấy file asset: " + fileAssetId);
  }
}
