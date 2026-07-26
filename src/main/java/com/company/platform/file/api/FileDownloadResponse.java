package com.company.platform.file.api;

import com.company.platform.file.application.FileDownloadResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record FileDownloadResponse(
    @Schema(
            description =
                "Presigned GET URL, thời hạn ngắn — chỉ sinh được khi storageStatus = CLEAN.",
            example = "https://minio:9000/export-results/9f8c1e2a-....csv?X-Amz-Signature=...")
        String downloadUrl,
    @Schema(description = "Thời điểm presigned URL hết hạn (15-30 phút theo thiết kế).")
        Instant expiresAt) {

  public static FileDownloadResponse from(FileDownloadResult result) {
    return new FileDownloadResponse(result.downloadUrl(), result.expiresAt());
  }
}
