package com.company.platform.file.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.pagination.KeysetCursor;
import com.company.platform.shared.pagination.PageSlice;
import com.company.platform.shared.pagination.TimeRange;
import com.company.platform.shared.pagination.UtcTimes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class FileAdminQueryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired FileAdminQueryService queryService;
  @Autowired FileCategoryRepository categoryRepository;
  @Autowired JobRepository jobRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.company.platform.file.domain.FileAssetRepository assetRepository;

  private static final Instant BASE = Instant.parse("2026-11-20T03:00:00Z");
  private static final TimeRange RANGE =
      new TimeRange(BASE.minus(10, ChronoUnit.DAYS), BASE.plus(10, ChronoUnit.DAYS));
  private String category;

  @BeforeEach
  void seedCategory() {
    category = "Q_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(category, "q-bucket", List.of("text/csv"), 10L, false, false, null, null));
  }

  private UUID insert(
      Instant createdAt, String filename, String mime, UUID jobId, Instant deletedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO file_assets (id, category_code, bucket_name, object_key, original_filename,"
            + " mime_type, size_bytes, storage_status, scan_status, owner_type, owner_id,"
            + " uploaded_by, job_id, created_at, updated_at, deleted_at)"
            + " VALUES (?, ?, 'q-bucket', ?, ?, ?, 100, 'CLEAN', 'SKIPPED', 'USER', ?, ?, ?, ?, ?, ?)",
        id,
        category,
        UUID.randomUUID().toString(),
        filename,
        mime,
        UUID.randomUUID(),
        UUID.randomUUID(),
        jobId,
        UtcTimes.toDb(createdAt),
        UtcTimes.toDb(createdAt),
        deletedAt == null ? null : UtcTimes.toDb(deletedAt));
    return id;
  }

  private UUID insert(Instant createdAt) {
    return insert(createdAt, "f.csv", "text/csv", null, null);
  }

  private FileSearchCriteria.Builder criteria() {
    return FileSearchCriteria.builder(RANGE).categoryCode(category);
  }

  private List<UUID> drain(FileSearchCriteria c, int limit) {
    List<UUID> all = new ArrayList<>();
    KeysetCursor cursor = null;
    for (int guard = 0; guard < 50; guard++) {
      PageSlice<FileAdminView> page = queryService.search(c, cursor, limit);
      page.items().forEach(v -> all.add(v.id()));
      if (page.nextCursor() == null) {
        return all;
      }
      cursor = KeysetCursor.decode(page.nextCursor());
    }
    throw new AssertionError("pagination did not terminate");
  }

  @Test
  void should_pageWithoutDuplicatesOrGaps_when_rowsShareCreatedAt() {
    List<Object[]> rows = new ArrayList<>();
    Instant shared = BASE;
    for (int i = 0; i < 3; i++) {
      rows.add(new Object[] {insert(shared), shared});
    }
    for (int i = 1; i <= 4; i++) {
      Instant t = BASE.minusSeconds(i * 60L);
      rows.add(new Object[] {insert(t), t});
    }
    List<UUID> expected =
        rows.stream()
            .sorted(
                Comparator.<Object[], Instant>comparing(r -> (Instant) r[1])
                    // Postgres so sánh uuid theo byte = thứ tự chuỗi hex, khác UUID.compareTo
                    .thenComparing(r -> r[0].toString())
                    .reversed())
            .map(r -> (UUID) r[0])
            .toList();

    List<UUID> paged = drain(criteria().build(), 2);

    assertThat(paged).containsExactlyElementsOf(expected);
  }

  @Test
  void should_returnNullCursor_when_lastPageExactlyFull() {
    insert(BASE);
    insert(BASE.minusSeconds(1));
    PageSlice<FileAdminView> page = queryService.search(criteria().build(), null, 2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void should_filterByMimePrefix_when_mimeEndsWithSlash() {
    UUID png = insert(BASE, "a.png", "image/png", null, null);
    UUID jpg = insert(BASE, "b.jpg", "image/jpeg", null, null);
    insert(BASE, "c.pdf", "application/pdf", null, null);

    assertThat(drain(criteria().mimeType("image/").build(), 50))
        .containsExactlyInAnyOrder(png, jpg);
    assertThat(drain(criteria().mimeType("image/png").build(), 50)).containsExactly(png);
  }

  @Test
  void should_excludeSoftDeleted_byDefault() {
    UUID live = insert(BASE);
    UUID deleted = insert(BASE, "d.csv", "text/csv", null, BASE.plusSeconds(5));

    assertThat(drain(criteria().build(), 50)).containsExactly(live);
    assertThat(drain(criteria().includeDeleted(true).build(), 50))
        .containsExactlyInAnyOrder(live, deleted);
  }

  @Test
  void should_filterBySource() {
    Job job =
        jobRepository.save(
            new Job(
                UUID.randomUUID(),
                JobType.EXPORT_USER_CSV,
                Map.of(),
                null,
                null,
                null,
                UUID.randomUUID()));
    UUID generated = insert(BASE, "e.csv", "text/csv", job.getId(), null);
    UUID uploaded = insert(BASE);

    assertThat(drain(criteria().source(FileSource.JOB_GENERATED).build(), 50))
        .containsExactly(generated);
    assertThat(drain(criteria().source(FileSource.USER_UPLOAD).build(), 50))
        .containsExactly(uploaded);
  }

  @Test
  void should_treatWildcardCharsLiterally_when_filenameContainsPercent() {
    UUID percent = insert(BASE, "bao-cao-50%.csv", "text/csv", null, null);
    insert(BASE, "bao-cao-500.csv", "text/csv", null, null);
    UUID underscore = insert(BASE, "a_b.csv", "text/csv", null, null);
    insert(BASE, "axb.csv", "text/csv", null, null);

    assertThat(drain(criteria().filename("50%").build(), 50)).containsExactly(percent);
    assertThat(drain(criteria().filename("A_B").build(), 50)).containsExactly(underscore);
  }

  @Test
  void should_filterByTimeRangeAndStatus() {
    UUID inside = insert(BASE);
    insert(BASE.minus(30, ChronoUnit.DAYS));
    jdbc.update("UPDATE file_assets SET storage_status = 'UPLOADING' WHERE id = ?", inside);

    assertThat(drain(criteria().build(), 50)).containsExactly(inside);
    assertThat(drain(criteria().storageStatuses(Set.of(StorageStatus.CLEAN)).build(), 50))
        .isEmpty();
    assertThat(drain(criteria().storageStatuses(Set.of(StorageStatus.UPLOADING)).build(), 50))
        .containsExactly(inside);
  }

  @Test
  void should_findHibernateWrittenRow_when_rangeAroundNow() {
    com.company.platform.file.domain.FileAsset asset =
        new com.company.platform.file.domain.FileAsset(
            UUID.randomUUID(),
            category,
            "q-bucket",
            UUID.randomUUID().toString(),
            "h.csv",
            "text/csv",
            5,
            "USER",
            UUID.randomUUID(),
            UUID.randomUUID(),
            null);
    assetRepository.save(asset);
    Instant now = Instant.now();
    // cửa sổ hẹp 2 phút: lệch múi giờ (VD +07) sẽ đẩy dòng ra ngoài
    TimeRange narrow = new TimeRange(now.minusSeconds(60), now.plusSeconds(60));

    PageSlice<FileAdminView> page =
        queryService.search(
            FileSearchCriteria.builder(narrow).categoryCode(category).build(), null, 10);

    assertThat(page.items()).extracting(FileAdminView::id).containsExactly(asset.getId());
    assertThat(page.items().get(0).createdAt()).isBetween(now.minusSeconds(60), now);
  }

  @Test
  void should_returnDeletedFile_when_getById() {
    UUID deleted = insert(BASE, "d.csv", "text/csv", null, BASE.plusSeconds(5));
    FileAdminView view = queryService.get(deleted);
    assertThat(view.deletedAt()).isEqualTo(BASE.plusSeconds(5));
    assertThat(view.categoryCode()).isEqualTo(category);
    assertThat(view.originalFilename()).isEqualTo("d.csv");
  }

  @Test
  void should_throwNotFound_when_getUnknownId() {
    assertThatThrownBy(() -> queryService.get(UUID.randomUUID()))
        .isInstanceOf(FileAssetNotFoundException.class);
  }
}
