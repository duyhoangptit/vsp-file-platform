package com.company.platform.file.api.admin;

import com.company.platform.file.application.admin.FileAdminQueryService;
import com.company.platform.file.application.admin.FileAdminView;
import com.company.platform.file.application.admin.FileCategoryAdminService;
import com.company.platform.file.application.admin.FileSearchCriteria;
import com.company.platform.file.application.admin.FileSource;
import com.company.platform.file.application.admin.FileStatsService;
import com.company.platform.file.domain.ScanStatus;
import com.company.platform.file.domain.StorageStatus;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/files")
@Tag(name = "Admin — file", description = "Danh sách (lịch sử upload), chi tiết, thống kê file")
@SecurityRequirement(name = "bearer-jwt")
public class FileAdminController {

  private final FileAdminQueryService queryService;
  private final FileCategoryAdminService categoryAdminService;
  private final FileStatsService statsService;
  private final Clock clock = Clock.systemUTC();

  public FileAdminController(
      FileAdminQueryService queryService,
      FileCategoryAdminService categoryAdminService,
      FileStatsService statsService) {
    this.queryService = queryService;
    this.categoryAdminService = categoryAdminService;
    this.statsService = statsService;
  }

  @Operation(
      summary = "Thống kê file",
      description =
          "Quyền file.asset.view. Chỉ tính file chưa soft-delete. from/to mặc định 30 ngày, tối"
              + " đa 92 ngày.")
  @ApiResponse(responseCode = "400", description = "Khoảng thời gian không hợp lệ")
  @GetMapping("/stats")
  public FileStatsResponse stats(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) String categoryCode) {
    return FileStatsResponse.from(
        statsService.stats(TimeRange.resolve(from, to, clock), blankToNull(categoryCode)));
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  @Operation(
      summary = "Danh sách file (lịch sử upload)",
      description =
          "Quyền file.asset.view. Mỗi dòng file_assets là 1 lần upload/sinh file, mới nhất trước."
              + " Phân trang keyset: truyền lại nextCursor. from/to mặc định 30 ngày gần nhất, tối"
              + " đa 92 ngày. mimeType kết thúc bằng '/' = lọc prefix (VD image/).")
  @ApiResponse(responseCode = "400", description = "cursor/khoảng thời gian/tham số không hợp lệ")
  @GetMapping
  public FileAdminPageResponse search(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(name = "storageStatus", required = false) List<StorageStatus> storageStatuses,
      @RequestParam(required = false) ScanStatus scanStatus,
      @RequestParam(required = false) String mimeType,
      @RequestParam(required = false) UUID uploadedBy,
      @RequestParam(required = false) String ownerType,
      @RequestParam(required = false) UUID ownerId,
      @Parameter(description = "Tên file chứa chuỗi (không phân biệt hoa thường)")
          @RequestParam(required = false)
          String filename,
      @RequestParam(required = false) FileSource source,
      @RequestParam(defaultValue = "false") boolean includeDeleted,
      @RequestParam(required = false) String cursor,
      @Parameter(description = "Mặc định 50, tối đa 200") @RequestParam(required = false)
          Integer limit) {
    KeysetCursor keyset = KeysetCursor.decodeOrNull(cursor);
    FileSearchCriteria criteria =
        FileSearchCriteria.builder(TimeRange.resolve(from, to, clock))
            .categoryCode(categoryCode)
            .storageStatuses(storageStatuses == null ? Set.of() : Set.copyOf(storageStatuses))
            .scanStatus(scanStatus)
            .mimeType(mimeType)
            .uploadedBy(uploadedBy)
            .ownerType(ownerType)
            .ownerId(ownerId)
            .filename(filename)
            .source(source)
            .includeDeleted(includeDeleted)
            .build();
    PageSlice<FileAdminView> page =
        queryService.search(criteria, keyset, PageSlice.clampLimit(limit));
    return new FileAdminPageResponse(
        page.items().stream().map(FileAdminItemResponse::from).toList(), page.nextCursor());
  }

  @Operation(
      summary = "Chi tiết file",
      description = "Quyền file.asset.view. Thấy cả file đã soft-delete.")
  @ApiResponse(responseCode = "404", description = "Không có file")
  @GetMapping("/{id}")
  public FileAdminDetailResponse get(@PathVariable UUID id) {
    FileAdminView view = queryService.get(id);
    boolean effective =
        categoryAdminService.effectiveVirusScan(categoryAdminService.get(view.categoryCode()));
    return FileAdminDetailResponse.from(view, effective);
  }
}
