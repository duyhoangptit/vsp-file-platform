package com.company.platform.shared.audit.api;

import com.company.platform.shared.audit.AuditAdminQueryService;
import com.company.platform.shared.audit.AuditEntryView;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.ConfigChangeItem;
import com.company.platform.shared.audit.DownloadChannel;
import com.company.platform.shared.audit.DownloadQuery;
import com.company.platform.shared.pagination.KeysetCursor;
import com.company.platform.shared.pagination.PageSlice;
import com.company.platform.shared.pagination.TimeRange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@Tag(
    name = "Admin — lịch sử",
    description = "Lịch sử download, lịch sử theo file, thay đổi cấu hình")
@SecurityRequirement(name = "bearer-jwt")
public class AuditAdminController {

  private final AuditAdminQueryService queryService;
  private final Clock clock = Clock.systemUTC();

  public AuditAdminController(AuditAdminQueryService queryService) {
    this.queryService = queryService;
  }

  @Operation(
      summary = "Lịch sử download",
      description =
          "Quyền file.history.view. Gồm: cấp link tải qua /api/files/{id}/download-url và"
              + " /api/templates/download-url (FILE_DOWNLOAD_URL_ISSUED), tải qua API nội bộ"
              + " (FILE_DOWNLOADED_INTERNAL), và lượt bị từ chối (READ DENIED). KHÔNG gồm file kết"
              + " quả export (link tải nhúng trong GET /exports/{id} — export có audit trail riêng)."
              + " Dữ liệu trước khi triển khai tính năng này chỉ có READ, xem"
              + " /api/admin/files/{id}/history. from/to mặc định 30 ngày, tối đa 92 ngày.")
  @ApiResponse(responseCode = "400", description = "cursor/khoảng thời gian/tham số không hợp lệ")
  @GetMapping("/file-downloads")
  public AuditPageResponse<FileDownloadHistoryItemResponse> downloads(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) UUID fileAssetId,
      @RequestParam(required = false) AuditResult result,
      @RequestParam(required = false) DownloadChannel channel,
      @RequestParam(required = false) String cursor,
      @Parameter(description = "Mặc định 50, tối đa 200") @RequestParam(required = false)
          Integer limit) {
    KeysetCursor keyset = KeysetCursor.decodeOrNull(cursor);
    DownloadQuery query =
        DownloadQuery.builder(TimeRange.resolve(from, to, clock))
            .actorId(actorId)
            .fileAssetId(fileAssetId)
            .result(result)
            .channel(channel)
            .build();
    var page = queryService.downloads(query, keyset, PageSlice.clampLimit(limit));
    return new AuditPageResponse<>(
        page.items().stream().map(FileDownloadHistoryItemResponse::from).toList(),
        page.nextCursor());
  }

  @Operation(
      summary = "Lịch sử của 1 file",
      description =
          "Quyền file.history.view. Mọi audit của file (upload, cấp link, READ/WRITE kể cả"
              + " DENIED), không giới hạn thời gian.")
  @GetMapping("/files/{id}/history")
  public AuditPageResponse<AuditEntryView> fileHistory(
      @PathVariable UUID id,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    PageSlice<AuditEntryView> page =
        queryService.forFile(id, KeysetCursor.decodeOrNull(cursor), PageSlice.clampLimit(limit));
    return new AuditPageResponse<>(page.items(), page.nextCursor());
  }

  @Operation(
      summary = "Lịch sử thay đổi cấu hình",
      description =
          "Quyền file.history.view. Bật/tắt công tắc tổng virus scan và sửa file category, kèm"
              + " giá trị cũ/mới trong metadata. resourceKey = category code hoặc"
              + " virus_scan.enabled.")
  @GetMapping("/config-changes")
  public AuditPageResponse<ConfigChangeItem> configChanges(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String resourceKey,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    KeysetCursor keyset = KeysetCursor.decodeOrNull(cursor);
    PageSlice<ConfigChangeItem> page =
        queryService.configChanges(
            TimeRange.resolve(from, to, clock),
            actorId,
            resourceKey == null || resourceKey.isBlank() ? null : resourceKey.trim(),
            keyset,
            PageSlice.clampLimit(limit));
    return new AuditPageResponse<>(page.items(), page.nextCursor());
  }
}
