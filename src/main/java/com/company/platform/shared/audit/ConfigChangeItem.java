package com.company.platform.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** {@code resourceKey} = category code hoặc setting key (metadata.resourceKey). */
public record ConfigChangeItem(
    UUID id,
    Instant occurredAt,
    UUID actorId,
    String action,
    String resourceKey,
    Map<String, Object> metadata) {}
