package com.company.platform.file.api.admin;

import com.company.platform.file.application.admin.FileAdminView;
import com.company.platform.file.domain.ScanStatus;
import com.company.platform.file.domain.StorageStatus;
import java.time.Instant;
import java.util.UUID;

public record FileAdminItemResponse(
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
    Instant deletedAt) {

  public static FileAdminItemResponse from(FileAdminView v) {
    return new FileAdminItemResponse(
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
        v.deletedAt());
  }
}
