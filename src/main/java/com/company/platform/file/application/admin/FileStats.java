package com.company.platform.file.application.admin;

import java.time.LocalDate;
import java.util.List;

public record FileStats(
    long totalFiles,
    long totalBytes,
    List<Bucket> byStorageStatus,
    List<Bucket> byCategory,
    List<Bucket> byMimeType,
    List<DailyBucket> daily) {

  public record Bucket(String key, long files, long bytes) {}

  public record DailyBucket(LocalDate date, long files, long bytes) {}
}
