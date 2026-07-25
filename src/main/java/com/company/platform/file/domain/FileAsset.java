package com.company.platform.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "file_assets")
@Getter
public class FileAsset {

  @Id private UUID id;

  @Column(name = "category_code", nullable = false)
  private String categoryCode;

  @Column(name = "bucket_name", nullable = false)
  private String bucketName;

  @Column(name = "object_key", nullable = false)
  private String objectKey;

  @Column(name = "original_filename")
  private String originalFilename;

  @Column(name = "mime_type", nullable = false)
  private String mimeType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "checksum_sha256")
  private String checksumSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "storage_status", nullable = false)
  private StorageStatus storageStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "scan_status", nullable = false)
  private ScanStatus scanStatus;

  @Column(name = "owner_type", nullable = false)
  private String ownerType;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "uploaded_by", nullable = false)
  private UUID uploadedBy;

  @Column(name = "job_id")
  private UUID jobId;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected FileAsset() {}

  public FileAsset(
      UUID id,
      String categoryCode,
      String bucketName,
      String objectKey,
      String originalFilename,
      String mimeType,
      long sizeBytes,
      String ownerType,
      UUID ownerId,
      UUID uploadedBy) {
    Instant now = Instant.now();
    this.id = id;
    this.categoryCode = categoryCode;
    this.bucketName = bucketName;
    this.objectKey = objectKey;
    this.originalFilename = originalFilename;
    this.mimeType = mimeType;
    this.sizeBytes = sizeBytes;
    this.storageStatus = StorageStatus.UPLOADING;
    this.scanStatus = ScanStatus.SKIPPED;
    this.ownerType = ownerType;
    this.ownerId = ownerId;
    this.uploadedBy = uploadedBy;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
