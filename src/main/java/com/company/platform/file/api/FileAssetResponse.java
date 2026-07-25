package com.company.platform.file.api;

import com.company.platform.file.domain.FileAsset;
import java.util.UUID;

public record FileAssetResponse(
    UUID id,
    String categoryCode,
    String originalFilename,
    String mimeType,
    long sizeBytes,
    String checksumSha256,
    String storageStatus) {

  public static FileAssetResponse from(FileAsset fileAsset) {
    return new FileAssetResponse(
        fileAsset.getId(),
        fileAsset.getCategoryCode(),
        fileAsset.getOriginalFilename(),
        fileAsset.getMimeType(),
        fileAsset.getSizeBytes(),
        fileAsset.getChecksumSha256(),
        fileAsset.getStorageStatus().name());
  }
}
