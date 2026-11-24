package com.company.platform.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntryView(
    UUID id,
    Instant occurredAt,
    UUID actorId,
    String action,
    String resourceType,
    UUID resourceId,
    AuditResult result,
    Map<String, Object> metadata) {

  static AuditEntryView from(AuditLog log) {
    return new AuditEntryView(
        log.getId(),
        log.getCreatedAt(),
        log.getActorId(),
        log.getAction(),
        log.getResourceType(),
        log.getResourceId(),
        log.getResult(),
        log.getMetadata());
  }
}
