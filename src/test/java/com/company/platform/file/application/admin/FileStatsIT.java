package com.company.platform.file.application.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.shared.pagination.TimeRange;
import com.company.platform.shared.pagination.UtcTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class FileStatsIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired FileStatsService statsService;
  @Autowired FileCategoryRepository categoryRepository;
  @Autowired JdbcTemplate jdbc;

  private static final Instant BASE = Instant.parse("2026-11-23T12:00:00Z");
  private static final TimeRange RANGE =
      new TimeRange(BASE.minus(5, ChronoUnit.DAYS), BASE.plus(5, ChronoUnit.DAYS));
  private String category;

  @BeforeEach
  void seedCategory() {
    category = "S_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(category, "s-bucket", List.of("text/csv"), 10L, false, false, null, null));
  }

  private void insert(Instant createdAt, String mime, long size, String status, boolean deleted) {
    jdbc.update(
        "INSERT INTO file_assets (id, category_code, bucket_name, object_key, original_filename,"
            + " mime_type, size_bytes, storage_status, scan_status, owner_type, owner_id,"
            + " uploaded_by, created_at, updated_at, deleted_at)"
            + " VALUES (?, ?, 's-bucket', ?, 'f', ?, ?, ?, 'SKIPPED', 'USER', ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        category,
        UUID.randomUUID().toString(),
        mime,
        size,
        status,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UtcTimes.toDb(createdAt),
        UtcTimes.toDb(createdAt),
        deleted ? UtcTimes.toDb(createdAt) : null);
  }

  @Test
  void should_bucketDailyInVietnamTime_when_fileCreatedAround17hUtc() {
    insert(Instant.parse("2026-11-23T17:30:00Z"), "text/csv", 10, "CLEAN", false);
    insert(Instant.parse("2026-11-23T16:59:00Z"), "text/csv", 20, "CLEAN", false);

    FileStats stats = statsService.stats(RANGE, category);

    assertThat(stats.daily())
        .containsExactly(
            new FileStats.DailyBucket(LocalDate.parse("2026-11-23"), 1, 20),
            new FileStats.DailyBucket(LocalDate.parse("2026-11-24"), 1, 10));
  }

  @Test
  void should_groupBeyondTop10AsOther() {
    for (int i = 0; i < 12; i++) {
      // mime i có (12 - i) file → thứ hạng xác định
      for (int n = 0; n < 12 - i; n++) {
        insert(BASE, "type/m" + (char) ('a' + i), 1, "CLEAN", false);
      }
    }

    FileStats stats = statsService.stats(RANGE, category);

    assertThat(stats.byMimeType()).hasSize(11);
    assertThat(stats.byMimeType().get(0)).isEqualTo(new FileStats.Bucket("type/ma", 12, 12));
    FileStats.Bucket other = stats.byMimeType().get(10);
    assertThat(other.key()).isEqualTo("OTHER");
    assertThat(other.files()).isEqualTo(2 + 1);
    assertThat(other.bytes()).isEqualTo(3);
  }

  @Test
  void should_excludeSoftDeleted() {
    insert(BASE, "text/csv", 100, "CLEAN", false);
    insert(BASE, "text/csv", 999, "CLEAN", true);

    FileStats stats = statsService.stats(RANGE, category);

    assertThat(stats.totalFiles()).isEqualTo(1);
    assertThat(stats.totalBytes()).isEqualTo(100);
  }

  @Test
  void should_sumBytesAndCounts_byStatusAndCategory() {
    insert(BASE, "text/csv", 100, "CLEAN", false);
    insert(BASE, "text/csv", 50, "CLEAN", false);
    insert(BASE, "image/png", 7, "UPLOADING", false);
    insert(BASE.minus(30, ChronoUnit.DAYS), "text/csv", 1000, "CLEAN", false);

    FileStats stats = statsService.stats(RANGE, category);

    assertThat(stats.totalFiles()).isEqualTo(3);
    assertThat(stats.totalBytes()).isEqualTo(157);
    assertThat(stats.byStorageStatus())
        .containsExactlyInAnyOrder(
            new FileStats.Bucket("CLEAN", 2, 150), new FileStats.Bucket("UPLOADING", 1, 7));
    assertThat(stats.byCategory()).containsExactly(new FileStats.Bucket(category, 3, 157));
    assertThat(stats.byMimeType())
        .containsExactly(
            new FileStats.Bucket("text/csv", 2, 150), new FileStats.Bucket("image/png", 1, 7));
  }

  @Test
  void should_returnZeros_when_noFiles() {
    FileStats stats = statsService.stats(RANGE, category);
    assertThat(stats.totalFiles()).isZero();
    assertThat(stats.totalBytes()).isZero();
    assertThat(stats.byMimeType()).isEmpty();
    assertThat(stats.daily()).isEmpty();
  }
}
