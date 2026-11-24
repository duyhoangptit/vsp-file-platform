package com.company.platform.shared.audit.api;

import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.DownloadChannel;
import com.company.platform.shared.audit.DownloadHistoryItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record FileDownloadHistoryItemResponse(
    UUID id,
    Instant occurredAt,
    UUID actorId,
    UUID fileAssetId,
    String action,
    DownloadChannel channel,
    AuditResult result,
    Map<String, Object> metadata,
    @Schema(description = "null nếu file không còn") String originalFilename,
    String mimeType,
    Long sizeBytes,
    String categoryCode) {

  static FileDownloadHistoryItemResponse from(DownloadHistoryItem i) {
    var f = i.file();
    return new FileDownloadHistoryItemResponse(
        i.id(),
        i.occurredAt(),
        i.actorId(),
        i.fileAssetId(),
        i.action(),
        i.channel(),
        i.result(),
        i.metadata(),
        f == null ? null : f.originalFilename(),
        f == null ? null : f.mimeType(),
        f == null ? null : f.sizeBytes(),
        f == null ? null : f.categoryCode());
  }
}
