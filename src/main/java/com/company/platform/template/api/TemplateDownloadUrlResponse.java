package com.company.platform.template.api;

import com.company.platform.file.application.FileDownloadResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record TemplateDownloadUrlResponse(
    @Schema(description = "Presigned GET URL, thời hạn ngắn.") String downloadUrl,
    @Schema(description = "Thời điểm presigned URL hết hạn.") Instant expiresAt) {

  public static TemplateDownloadUrlResponse from(FileDownloadResult result) {
    return new TemplateDownloadUrlResponse(result.downloadUrl(), result.expiresAt());
  }
}
