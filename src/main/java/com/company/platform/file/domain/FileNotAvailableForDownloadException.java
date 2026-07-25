package com.company.platform.file.domain;

import java.util.UUID;

public class FileNotAvailableForDownloadException extends RuntimeException {

  public FileNotAvailableForDownloadException(UUID fileAssetId, StorageStatus currentStatus) {
    super(
        "File asset %s chưa sẵn sàng để tải xuống (trạng thái hiện tại: %s)"
            .formatted(fileAssetId, currentStatus));
  }
}
