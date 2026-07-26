package com.company.platform.file.api;

import com.company.platform.file.application.FileUploadInitiation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record InitiateFileUploadResponse(
    @Schema(
            description =
                "ID file asset vừa tạo (storageStatus = UPLOADING) — dùng ở bước confirm.")
        UUID fileAssetId,
    @Schema(
            description =
                "Presigned PUT URL — client upload thẳng lên MinIO bằng URL này (bypass backend),"
                    + " không qua server.",
            example = "https://minio:9000/import-raw/9f8c1e2a-....csv?X-Amz-Signature=...")
        String uploadUrl,
    @Schema(description = "Thời điểm presigned URL hết hạn (15-30 phút theo thiết kế).")
        Instant expiresAt) {

  public static InitiateFileUploadResponse from(FileUploadInitiation initiation) {
    return new InitiateFileUploadResponse(
        initiation.fileAssetId(), initiation.uploadUrl(), initiation.expiresAt());
  }
}
