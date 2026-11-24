package com.company.platform.file.api.admin;

import com.company.platform.file.application.admin.FileAdminView;
import com.company.platform.file.domain.ScanStatus;
import com.company.platform.file.domain.StorageStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record FileAdminDetailResponse(
    UUID id,
    String originalFilename,
    String mimeType,
    long sizeBytes,
    String categoryCode,
    StorageStatus storageStatus,
    ScanStatus scanStatus,
    String ownerType,
    UUID ownerId,
    UUID uploadedBy,
    UUID jobId,
    String checksumSha256,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt,
    Instant deletedAt,
    @Schema(description = "Virus scan có hiệu lực cho category của file, tại thời điểm hiện tại")
        boolean effectiveVirusScan) {

  public static FileAdminDetailResponse from(FileAdminView v, boolean effectiveVirusScan) {
    return new FileAdminDetailResponse(
        v.id(),
        v.originalFilename(),
        v.mimeType(),
        v.sizeBytes(),
        v.categoryCode(),
        v.storageStatus(),
        v.scanStatus(),
        v.ownerType(),
        v.ownerId(),
        v.uploadedBy(),
        v.jobId(),
        v.checksumSha256(),
        v.createdAt(),
        v.updatedAt(),
        v.expiresAt(),
        v.deletedAt(),
        effectiveVirusScan);
  }
}
