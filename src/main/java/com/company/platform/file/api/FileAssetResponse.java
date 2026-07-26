package com.company.platform.file.api;

import com.company.platform.file.domain.FileAsset;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record FileAssetResponse(
    @Schema(description = "ID file asset.") UUID id,
    @Schema(description = "Mã file_categories của file này.", example = "IMPORT_RAW")
        String categoryCode,
    @Schema(description = "Tên file gốc (chỉ hiển thị UI).", example = "users-2026-07.csv")
        String originalFilename,
    @Schema(description = "MIME type đã xác nhận.", example = "text/csv") String mimeType,
    @Schema(
            description = "Kích thước file (bytes), xác nhận qua MinIO HEAD object.",
            example = "1048576")
        long sizeBytes,
    @Schema(
            description = "SHA-256 checksum client gửi lên ở bước confirm.",
            example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        String checksumSha256,
    @Schema(
            description =
                "UPLOADING -> UPLOADED_DMZ -> CLEAN (chưa bật virus scan) hoặc -> SCANNING ->"
                    + " CLEAN|INFECTED -> QUARANTINED (khi bật scan). Presigned download URL chỉ"
                    + " sinh được khi CLEAN.",
            example = "CLEAN")
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
