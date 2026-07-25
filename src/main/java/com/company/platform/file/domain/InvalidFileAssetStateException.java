package com.company.platform.file.domain;

import java.util.UUID;

public class InvalidFileAssetStateException extends RuntimeException {

  public InvalidFileAssetStateException(UUID fileAssetId, StorageStatus currentStatus) {
    super(
        "File asset %s đang ở trạng thái %s, không thể confirm upload"
            .formatted(fileAssetId, currentStatus));
  }
}
