package com.company.platform.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_logs")
@Getter
public class AuditLog {

  @Id private UUID id;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(name = "action", nullable = false)
  private String action;

  @Column(name = "resource_type", nullable = false)
  private String resourceType;

  @Column(name = "resource_id", nullable = false)
  private UUID resourceId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata")
  private Map<String, Object> metadata;

  @Enumerated(EnumType.STRING)
  @Column(name = "result", nullable = false)
  private AuditResult result;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditLog() {}

  public AuditLog(
      UUID actorId,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> metadata,
      AuditResult result) {
    this.id = UUID.randomUUID();
    this.actorId = actorId;
    this.action = action;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
    this.metadata = metadata;
    this.result = result;
    this.createdAt = Instant.now();
  }
}
