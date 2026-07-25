package com.company.platform.job.domain;

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
@Table(name = "jobs")
@Getter
public class Job {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "job_type", nullable = false)
  private JobType jobType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private JobStatus status;

  @Column(name = "priority", nullable = false)
  private short priority;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  private Map<String, Object> payload;

  @Column(name = "result_location")
  private String resultLocation;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "total_units")
  private Long totalUnits;

  @Column(name = "processed_units", nullable = false)
  private long processedUnits;

  @Column(name = "heartbeat_at")
  private Instant heartbeatAt;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Job() {}

  public Job(
      UUID id,
      JobType jobType,
      Map<String, Object> payload,
      Short priority,
      Long totalUnits,
      String idempotencyKey,
      UUID createdBy) {
    Instant now = Instant.now();
    this.id = id;
    this.jobType = jobType;
    this.status = JobStatus.PENDING;
    this.priority = priority != null ? priority : 0;
    this.payload = payload;
    this.totalUnits = totalUnits;
    this.processedUnits = 0;
    this.retryCount = 0;
    this.idempotencyKey = idempotencyKey;
    this.createdBy = createdBy;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
