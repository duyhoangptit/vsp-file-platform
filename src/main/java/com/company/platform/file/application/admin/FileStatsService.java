package com.company.platform.file.application.admin;

import com.company.platform.file.domain.FileAssetAdminQueryRepository;
import com.company.platform.shared.config.AdminProperties;
import com.company.platform.shared.pagination.TimeRange;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Thống kê file (chỉ file chưa soft-delete) trong khoảng thời gian, tuỳ chọn theo category. */
@Service
@Transactional(readOnly = true)
public class FileStatsService {

  static final int TOP_MIME_TYPES = 10;
  static final String OTHER = "OTHER";

  private final FileAssetAdminQueryRepository repository;
  private final AdminProperties adminProperties;

  public FileStatsService(
      FileAssetAdminQueryRepository repository, AdminProperties adminProperties) {
    this.repository = repository;
    this.adminProperties = adminProperties;
  }

  public FileStats stats(TimeRange range, String categoryCode) {
    FileSearchCriteria c = FileSearchCriteria.builder(range).categoryCode(categoryCode).build();
    List<FileStats.Bucket> byStatus = repository.groupBy(c, "storage_status");
    long files = byStatus.stream().mapToLong(FileStats.Bucket::files).sum();
    long bytes = byStatus.stream().mapToLong(FileStats.Bucket::bytes).sum();
    return new FileStats(
        files,
        bytes,
        byStatus,
        repository.groupBy(c, "category_code"),
        topWithOther(repository.groupBy(c, "mime_type")),
        repository.daily(c, adminProperties.statsZone()));
  }

  private static List<FileStats.Bucket> topWithOther(List<FileStats.Bucket> sorted) {
    if (sorted.size() <= TOP_MIME_TYPES) {
      return sorted;
    }
    List<FileStats.Bucket> out = new ArrayList<>(sorted.subList(0, TOP_MIME_TYPES));
    List<FileStats.Bucket> rest = sorted.subList(TOP_MIME_TYPES, sorted.size());
    out.add(
        new FileStats.Bucket(
            OTHER,
            rest.stream().mapToLong(FileStats.Bucket::files).sum(),
            rest.stream().mapToLong(FileStats.Bucket::bytes).sum()));
    return out;
  }
}
