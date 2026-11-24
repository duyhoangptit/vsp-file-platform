package com.company.platform.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** {@code file} null = file không còn trong file_assets. */
public record DownloadHistoryItem(
    UUID id,
    Instant occurredAt,
    UUID actorId,
    UUID fileAssetId,
    String action,
    DownloadChannel channel,
    AuditResult result,
    Map<String, Object> metadata,
    FileAssetSummary file) {}
