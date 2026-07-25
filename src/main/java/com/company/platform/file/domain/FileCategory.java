package com.company.platform.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
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

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected FileCategory() {}

  public FileCategory(
      String code,
      String bucketName,
      List<String> allowedMimeTypes,
      long maxSizeBytes,
      boolean requireVirusScan,
      boolean publicAccess,
      Integer retentionDays) {
    Instant now = Instant.now();
    this.code = code;
    this.bucketName = bucketName;
    this.allowedMimeTypes = allowedMimeTypes;
    this.maxSizeBytes = maxSizeBytes;
    this.requireVirusScan = requireVirusScan;
    this.publicAccess = publicAccess;
    this.retentionDays = retentionDays;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
