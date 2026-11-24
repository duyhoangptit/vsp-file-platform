package com.company.platform.shared.audit;

import com.company.platform.shared.pagination.KeysetCursor;
import com.company.platform.shared.pagination.PageSlice;
import com.company.platform.shared.pagination.TimeRange;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lịch sử download (không gồm link nhúng trong GET /exports/{id}), lịch sử theo file, lịch sử thay
 * đổi cấu hình — đọc từ audit_logs.
 */
@Service
@Transactional(readOnly = true)
public class AuditAdminQueryService {

  private final AuditAdminQueryRepository repository;
  private final FileAssetSummaryLookup fileAssetSummaryLookup;

  public AuditAdminQueryService(
      AuditAdminQueryRepository repository, FileAssetSummaryLookup fileAssetSummaryLookup) {
    this.repository = repository;
    this.fileAssetSummaryLookup = fileAssetSummaryLookup;
  }

  public PageSlice<DownloadHistoryItem> downloads(DownloadQuery q, KeysetCursor cursor, int limit) {
    PageSlice<AuditLog> page = slice(repository.downloads(q, cursor, limit + 1), limit);
    Map<UUID, FileAssetSummary> files =
        fileAssetSummaryLookup.findSummaries(
            page.items().stream().map(AuditLog::getResourceId).distinct().toList());
    return map(
        page,
        log ->
            new DownloadHistoryItem(
                log.getId(),
                log.getCreatedAt(),
                log.getActorId(),
                log.getResourceId(),
                log.getAction(),
                FileAuditActions.DOWNLOADED_INTERNAL.equals(log.getAction())
                    ? DownloadChannel.INTERNAL
                    : DownloadChannel.PRESIGNED,
                log.getResult(),
                log.getMetadata(),
                files.get(log.getResourceId())));
  }

  public PageSlice<AuditEntryView> forFile(UUID fileAssetId, KeysetCursor cursor, int limit) {
    return map(
        slice(repository.forFile(fileAssetId, cursor, limit + 1), limit), AuditEntryView::from);
  }

  public PageSlice<ConfigChangeItem> configChanges(
      TimeRange range, UUID actorId, String resourceKey, KeysetCursor cursor, int limit) {
    return map(
        slice(repository.configChanges(range, actorId, resourceKey, cursor, limit + 1), limit),
        log ->
            new ConfigChangeItem(
                log.getId(),
                log.getCreatedAt(),
                log.getActorId(),
                log.getAction(),
                log.getMetadata() == null ? null : (String) log.getMetadata().get("resourceKey"),
                log.getMetadata()));
  }

  private static PageSlice<AuditLog> slice(List<AuditLog> rows, int limit) {
    if (rows.size() <= limit) {
      return new PageSlice<>(rows, null);
    }
    List<AuditLog> page = List.copyOf(rows.subList(0, limit));
    AuditLog last = page.get(limit - 1);
    return new PageSlice<>(page, new KeysetCursor(last.getCreatedAt(), last.getId()).encode());
  }

  private static <T> PageSlice<T> map(PageSlice<AuditLog> page, Function<AuditLog, T> mapper) {
    return new PageSlice<>(page.items().stream().map(mapper).toList(), page.nextCursor());
  }
}
