package com.company.platform.template.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "file_template_manager")
@Getter
public class FileTemplate {

  @Id private UUID id;

  @Column(name = "category", nullable = false)
  private String category;

  @Column(name = "template_code", nullable = false)
  private String templateCode;

  @Column(name = "version", nullable = false)
  private int version;

  @Column(name = "file_id", nullable = false)
  private UUID fileId;

  @Column(name = "description")
  private String description;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected FileTemplate() {}

  public FileTemplate(
      UUID id,
      String category,
      String templateCode,
      int version,
      UUID fileId,
      String description,
      UUID createdBy) {
    Instant now = Instant.now();
    this.id = id;
    this.category = category;
    this.templateCode = templateCode;
    this.version = version;
    this.fileId = fileId;
    this.description = description;
    this.createdBy = createdBy;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
