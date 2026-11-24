package com.company.platform.file.api.admin;

import com.company.platform.file.domain.FileCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FileCategoryAdminResponse(
    String code,
    String bucketName,
    List<String> allowedMimeTypes,
    long maxSizeBytes,
    boolean requireVirusScan,
    @Schema(description = "Công tắc tổng && requireVirusScan") boolean effectiveVirusScan,
    boolean publicAccess,
    @Schema(description = "null = giữ vĩnh viễn") Integer retentionDays,
    Long multipartPartSizeBytes,
    @Schema(description = "Gửi lại khi PATCH (optimistic lock)") long version,
    UUID updatedBy,
    Instant updatedAt) {

  public static FileCategoryAdminResponse from(FileCategory c, boolean effectiveVirusScan) {
    return new FileCategoryAdminResponse(
        c.getCode(),
        c.getBucketName(),
        c.getAllowedMimeTypes(),
        c.getMaxSizeBytes(),
        c.isRequireVirusScan(),
        effectiveVirusScan,
        c.isPublicAccess(),
        c.getRetentionDays(),
        c.getMultipartPartSizeBytes(),
        c.getVersion(),
        c.getUpdatedBy(),
        c.getUpdatedAt());
  }
}
