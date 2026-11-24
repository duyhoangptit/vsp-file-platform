package com.company.platform.file.application.admin;

import com.company.platform.file.domain.ScanStatus;
import com.company.platform.file.domain.StorageStatus;
import java.time.Instant;
import java.util.UUID;

/** 1 dòng file_assets cho admin — cố tình không có bucketName/objectKey. */
public record FileAdminView(
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
    Instant deletedAt) {}
