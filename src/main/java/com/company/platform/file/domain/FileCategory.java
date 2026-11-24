package com.company.platform.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "file_categories")
@Getter
public class FileCategory {

  @Id private String code;

  @Column(name = "bucket_name", nullable = false)
  private String bucketName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "allowed_mime_types", nullable = false)
  private List<String> allowedMimeTypes;

  @Column(name = "max_size_bytes", nullable = false)
  private long maxSizeBytes;

  @Column(name = "require_virus_scan", nullable = false)
  private boolean requireVirusScan;

  @Column(name = "is_public", nullable = false)
  private boolean publicAccess;

  @Column(name = "retention_days")
  private Integer retentionDays;

  @Column(name = "multipart_part_size_bytes")
  private Long multipartPartSizeBytes;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "updated_by")
  private UUID updatedBy;

  protected FileCategory() {}

  public FileCategory(
      String code,
      String bucketName,
      List<String> allowedMimeTypes,
      long maxSizeBytes,
      boolean requireVirusScan,
      boolean publicAccess,
      Integer retentionDays,
      Long multipartPartSizeBytes) {
    Instant now = Instant.now();
    this.code = code;
    this.bucketName = bucketName;
    this.allowedMimeTypes = allowedMimeTypes;
    this.maxSizeBytes = maxSizeBytes;
    this.requireVirusScan = requireVirusScan;
    this.publicAccess = publicAccess;
    this.retentionDays = retentionDays;
    this.multipartPartSizeBytes = multipartPartSizeBytes;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Sửa cấu hình qua admin API: chỉ đổi field khác null; {@code clearRetention=true} xoá retention
   * (giữ file vĩnh viễn). Không cho đổi code/bucket/publicAccess/multipart part size.
   */
  public void applyAdminUpdate(
      Boolean requireVirusScan,
      List<String> allowedMimeTypes,
      Long maxSizeBytes,
      Integer retentionDays,
      boolean clearRetention,
      UUID actorId) {
    if (requireVirusScan != null) {
      this.requireVirusScan = requireVirusScan;
    }
    if (allowedMimeTypes != null) {
      this.allowedMimeTypes = List.copyOf(allowedMimeTypes);
    }
    if (maxSizeBytes != null) {
      this.maxSizeBytes = maxSizeBytes;
    }
    if (clearRetention) {
      this.retentionDays = null;
    } else if (retentionDays != null) {
      this.retentionDays = retentionDays;
    }
    this.updatedBy = actorId;
    this.updatedAt = Instant.now();
  }
}
