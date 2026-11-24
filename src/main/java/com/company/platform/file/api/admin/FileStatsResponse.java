package com.company.platform.file.api.admin;

import com.company.platform.file.application.admin.FileStats;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record FileStatsResponse(
    long totalFiles,
    long totalBytes,
    List<Bucket> byStorageStatus,
    List<Bucket> byCategory,
    @Schema(description = "Top 10 theo số file; phần còn lại gộp key OTHER")
        List<Bucket> byMimeType,
    @Schema(
            description =
                "Theo ngày ở app.admin.stats-zone (mặc định Asia/Ho_Chi_Minh), chỉ ngày có dữ liệu")
        List<Daily> daily) {

  public record Bucket(String key, long files, long bytes) {}

  public record Daily(LocalDate date, long files, long bytes) {}

  public static FileStatsResponse from(FileStats s) {
    return new FileStatsResponse(
        s.totalFiles(),
        s.totalBytes(),
        buckets(s.byStorageStatus()),
        buckets(s.byCategory()),
        buckets(s.byMimeType()),
        s.daily().stream().map(d -> new Daily(d.date(), d.files(), d.bytes())).toList());
  }

  private static List<Bucket> buckets(List<FileStats.Bucket> in) {
    return in.stream().map(b -> new Bucket(b.key(), b.files(), b.bytes())).toList();
  }
}
