package com.company.platform.template.api;

import com.company.platform.template.application.TemplateUploadInitiation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record TemplateUploadInitiationResponse(
    @Schema(description = "ID file asset vừa tạo — dùng ở bước confirm.") UUID fileAssetId,
    @Schema(description = "Presigned PUT URL — client upload thẳng lên MinIO.") String uploadUrl,
    @Schema(description = "Thời điểm presigned URL hết hạn.") Instant expiresAt) {

  public static TemplateUploadInitiationResponse from(TemplateUploadInitiation initiation) {
    return new TemplateUploadInitiationResponse(
        initiation.fileAssetId(), initiation.uploadUrl(), initiation.expiresAt());
  }
}
