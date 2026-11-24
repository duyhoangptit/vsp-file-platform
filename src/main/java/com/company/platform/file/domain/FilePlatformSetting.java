package com.company.platform.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Cấu hình runtime toàn hệ thống (key/value JSONB), admin chỉnh qua {@code /api/admin/**}. */
@Entity
@Table(name = "file_platform_settings")
@Getter
public class FilePlatformSetting {

  /** Công tắc tổng virus scan: {@code true|false}. */
  public static final String VIRUS_SCAN_ENABLED = "virus_scan.enabled";

  @Id
  @Column(name = "setting_key")
  private String settingKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "setting_value", nullable = false)
  private Object settingValue;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected FilePlatformSetting() {}

  public boolean booleanValue() {
    return Boolean.TRUE.equals(settingValue);
  }

  public void update(Object newValue, UUID actorId) {
    this.settingValue = newValue;
    this.updatedBy = actorId;
    this.updatedAt = Instant.now();
  }
}
