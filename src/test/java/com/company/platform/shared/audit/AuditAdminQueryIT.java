package com.company.platform.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.shared.pagination.KeysetCursor;
import com.company.platform.shared.pagination.PageSlice;
import com.company.platform.shared.pagination.TimeRange;
import com.company.platform.shared.pagination.UtcTimes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
class AuditAdminQueryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired AuditAdminQueryService queryService;
  @Autowired FileCategoryRepository categoryRepository;
  @Autowired JdbcTemplate jdbc;

  private static final Instant BASE = Instant.parse("2026-11-20T03:00:00Z");
  private static final TimeRange RANGE =
      new TimeRange(BASE.minus(5, ChronoUnit.DAYS), BASE.plus(5, ChronoUnit.DAYS));
  private UUID actor;
  private String category;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM audit_logs");
    actor = UUID.randomUUID();
    category = "H_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(category, "h-bucket", List.of("text/csv"), 10L, false, false, null, null));
  }

  private UUID audit(
      String action, String resourceType, UUID resourceId, String result, Instant at, String meta) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO audit_logs (id, actor_id, action, resource_type, resource_id, metadata,"
            + " result, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
        id,
        actor,
        action,
        resourceType,
        resourceId,
        meta,
        result,
        UtcTimes.toDb(at));
    return id;
  }

  private UUID fileAudit(String action, UUID fileId, String result, Instant at) {
    return audit(action, "FILE_ASSET", fileId, result, at, null);
  }

  private UUID file(String name, Instant deletedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO file_assets (id, category_code, bucket_name, object_key, original_filename,"
            + " mime_type, size_bytes, storage_status, scan_status, owner_type, owner_id,"
            + " uploaded_by, created_at, updated_at, deleted_at)"
            + " VALUES (?, ?, 'h-bucket', ?, ?, 'text/csv', 42, 'CLEAN', 'SKIPPED', 'USER', ?, ?, ?, ?, ?)",
        id,
        category,
        UUID.randomUUID().toString(),
        name,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UtcTimes.toDb(BASE),
        UtcTimes.toDb(BASE),
        deletedAt == null ? null : UtcTimes.toDb(deletedAt));
    return id;
  }

  private DownloadQuery.Builder downloads() {
    return DownloadQuery.builder(RANGE);
  }

  @Test
  void should_listOnlyDownloadActionsAndDeniedReads() {
    UUID f = UUID.randomUUID();
    UUID issued = fileAudit("FILE_DOWNLOAD_URL_ISSUED", f, "SUCCESS", BASE);
    UUID internal = fileAudit("FILE_DOWNLOADED_INTERNAL", f, "SUCCESS", BASE.minusSeconds(1));
    UUID denied = fileAudit("READ", f, "DENIED", BASE.minusSeconds(2));
    fileAudit("READ", f, "SUCCESS", BASE.minusSeconds(3));
    fileAudit("WRITE", f, "SUCCESS", BASE.minusSeconds(4));
    fileAudit("FILE_UPLOAD_COMPLETED", f, "SUCCESS", BASE.minusSeconds(5));

    PageSlice<DownloadHistoryItem> page = queryService.downloads(downloads().build(), null, 50);

    assertThat(page.items())
        .extracting(DownloadHistoryItem::id)
        .containsExactly(issued, internal, denied);
    assertThat(page.items())
        .extracting(DownloadHistoryItem::channel)
        .containsExactly(
            DownloadChannel.PRESIGNED, DownloadChannel.INTERNAL, DownloadChannel.PRESIGNED);
    assertThat(page.items().get(2).result()).isEqualTo(AuditResult.DENIED);
  }

  @Test
  void should_filterByChannelAndResult() {
    UUID f = UUID.randomUUID();
    fileAudit("FILE_DOWNLOAD_URL_ISSUED", f, "SUCCESS", BASE);
    UUID internal = fileAudit("FILE_DOWNLOADED_INTERNAL", f, "SUCCESS", BASE.minusSeconds(1));
    UUID denied = fileAudit("READ", f, "DENIED", BASE.minusSeconds(2));

    assertThat(
            queryService
                .downloads(downloads().channel(DownloadChannel.INTERNAL).build(), null, 50)
                .items())
        .extracting(DownloadHistoryItem::id)
        .containsExactly(internal);
    assertThat(
            queryService
                .downloads(downloads().result(AuditResult.DENIED).build(), null, 50)
                .items())
        .extracting(DownloadHistoryItem::id)
        .containsExactly(denied);
    assertThat(
            queryService
                .downloads(downloads().fileAssetId(UUID.randomUUID()).build(), null, 50)
                .items())
        .isEmpty();
  }

  @Test
  void should_pageWithoutDuplicates_when_rowsShareCreatedAt() {
    UUID f = UUID.randomUUID();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ids.add(fileAudit("FILE_DOWNLOAD_URL_ISSUED", f, "SUCCESS", BASE));
    }
    for (int i = 1; i <= 2; i++) {
      ids.add(fileAudit("FILE_DOWNLOAD_URL_ISSUED", f, "SUCCESS", BASE.minusSeconds(i)));
    }

    List<UUID> seen = new ArrayList<>();
    KeysetCursor cursor = null;
    for (int guard = 0; guard < 20; guard++) {
      PageSlice<DownloadHistoryItem> page = queryService.downloads(downloads().build(), cursor, 2);
      page.items().forEach(i -> seen.add(i.id()));
      if (page.nextCursor() == null) {
        break;
      }
      cursor = KeysetCursor.decode(page.nextCursor());
    }

    assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
  }

  @Test
  void should_enrichWithFileSummary_including_softDeletedFile() {
    UUID live = file("live.csv", null);
    UUID gone = file("gone.csv", BASE);
    UUID missing = UUID.randomUUID();
    fileAudit("FILE_DOWNLOAD_URL_ISSUED", live, "SUCCESS", BASE);
    fileAudit("FILE_DOWNLOAD_URL_ISSUED", gone, "SUCCESS", BASE.minusSeconds(1));
    fileAudit("FILE_DOWNLOAD_URL_ISSUED", missing, "SUCCESS", BASE.minusSeconds(2));

    List<DownloadHistoryItem> items = queryService.downloads(downloads().build(), null, 50).items();

    assertThat(items.get(0).file().originalFilename()).isEqualTo("live.csv");
    assertThat(items.get(0).file().sizeBytes()).isEqualTo(42);
    assertThat(items.get(0).file().categoryCode()).isEqualTo(category);
    assertThat(items.get(1).file().originalFilename()).isEqualTo("gone.csv");
    assertThat(items.get(2).file()).isNull();
  }

  @Test
  void should_filterConfigChangesByResourceKey() {
    UUID cat =
        audit(
            "FILE_CATEGORY_UPDATED",
            "FILE_CATEGORY",
            AuditResourceIds.forCategory("AVATAR"),
            "SUCCESS",
            BASE,
            "{\"resourceKey\":\"AVATAR\",\"changes\":{\"maxSizeBytes\":{\"old\":1,\"new\":2}}}");
    UUID global =
        audit(
            "VIRUS_SCAN_GLOBAL_UPDATED",
            "FILE_PLATFORM_SETTING",
            AuditResourceIds.forSetting("virus_scan.enabled"),
            "SUCCESS",
            BASE.minusSeconds(1),
            "{\"resourceKey\":\"virus_scan.enabled\",\"old\":false,\"new\":true}");
    fileAudit("FILE_DOWNLOAD_URL_ISSUED", UUID.randomUUID(), "SUCCESS", BASE);

    assertThat(queryService.configChanges(RANGE, null, null, null, 50).items())
        .extracting(ConfigChangeItem::id)
        .containsExactly(cat, global);
    List<ConfigChangeItem> avatar =
        queryService.configChanges(RANGE, null, "AVATAR", null, 50).items();
    assertThat(avatar).extracting(ConfigChangeItem::id).containsExactly(cat);
    assertThat(avatar.get(0).resourceKey()).isEqualTo("AVATAR");
    assertThat(avatar.get(0).metadata()).containsKey("changes");
  }

  @Test
  void should_returnAllActionsForFile_when_readingFileHistory() {
    UUID f = UUID.randomUUID();
    UUID a = fileAudit("FILE_UPLOAD_COMPLETED", f, "SUCCESS", BASE.minus(200, ChronoUnit.DAYS));
    UUID b = fileAudit("READ", f, "SUCCESS", BASE);
    UUID c = fileAudit("WRITE", f, "DENIED", BASE.plusSeconds(1));
    fileAudit("READ", UUID.randomUUID(), "SUCCESS", BASE);

    assertThat(queryService.forFile(f, null, 50).items())
        .extracting(AuditEntryView::id)
        .containsExactly(c, b, a);
  }
}
