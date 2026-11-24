package com.company.platform.file.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.application.admin.FileAdminQueryService;
import com.company.platform.file.application.admin.FileAdminView;
import com.company.platform.file.application.admin.FileCategoryAdminService;
import com.company.platform.file.application.admin.FileSearchCriteria;
import com.company.platform.file.application.admin.FileSource;
import com.company.platform.file.application.admin.FileStats;
import com.company.platform.file.application.admin.FileStatsService;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.ScanStatus;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.shared.pagination.KeysetCursor;
import com.company.platform.shared.pagination.PageSlice;
import com.company.platform.shared.pagination.TimeRange;
import com.company.platform.shared.security.AdminActor;
import com.company.platform.shared.security.FilePermissions;
import com.company.platform.shared.security.SecurityConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(FileAdminController.class)
@Import({SecurityConfig.class, AdminActor.class})
class FileAdminControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private FileAdminQueryService queryService;
  @MockitoBean private FileCategoryAdminService categoryAdminService;
  @MockitoBean private FileStatsService statsService;

  private static RequestPostProcessor viewer() {
    return jwt()
        .jwt(j -> j.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority(FilePermissions.ASSET_VIEW));
  }

  private static FileAdminView view(UUID id) {
    Instant t = Instant.parse("2026-11-20T03:00:00Z");
    return new FileAdminView(
        id,
        "a.csv",
        "text/csv",
        100,
        "CAT",
        StorageStatus.CLEAN,
        ScanStatus.SKIPPED,
        "USER",
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        "sha",
        t,
        t,
        null,
        null);
  }

  @Test
  void should_return400_when_cursorIsGarbage() throws Exception {
    mockMvc
        .perform(get("/api/admin/files").param("cursor", "!!!").with(viewer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
    verify(queryService, never()).search(any(), any(), anyInt());
  }

  @Test
  void should_return400_when_rangeLongerThan92Days() throws Exception {
    mockMvc
        .perform(
            get("/api/admin/files")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-06-01T00:00:00Z")
                .with(viewer()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return400_when_enumParamInvalid() throws Exception {
    mockMvc
        .perform(get("/api/admin/files").param("storageStatus", "NOPE").with(viewer()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_clampLimitAndMapFilters_when_searching() throws Exception {
    UUID id = UUID.randomUUID();
    when(queryService.search(any(), any(), anyInt()))
        .thenReturn(new PageSlice<>(List.of(view(id)), "next-cursor"));
    KeysetCursor cursor =
        new KeysetCursor(Instant.parse("2026-11-20T00:00:00Z"), UUID.randomUUID());

    mockMvc
        .perform(
            get("/api/admin/files")
                .param("limit", "1000")
                .param("cursor", cursor.encode())
                .param("categoryCode", "CAT")
                .param("storageStatus", "CLEAN", "UPLOADING")
                .param("mimeType", "image/")
                .param("source", "JOB_GENERATED")
                .param("filename", "bao cao")
                .with(viewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(id.toString()))
        .andExpect(jsonPath("$.items[0].originalFilename").value("a.csv"))
        .andExpect(jsonPath("$.items[0].bucketName").doesNotExist())
        .andExpect(jsonPath("$.items[0].objectKey").doesNotExist())
        .andExpect(jsonPath("$.nextCursor").value("next-cursor"));

    ArgumentCaptor<FileSearchCriteria> criteria = ArgumentCaptor.forClass(FileSearchCriteria.class);
    verify(queryService).search(criteria.capture(), eq(cursor), eq(200));
    FileSearchCriteria c = criteria.getValue();
    assertThat(c.categoryCode()).isEqualTo("CAT");
    assertThat(c.storageStatuses())
        .containsExactlyInAnyOrder(StorageStatus.CLEAN, StorageStatus.UPLOADING);
    assertThat(c.mimeType()).isEqualTo("image/");
    assertThat(c.source()).isEqualTo(FileSource.JOB_GENERATED);
    assertThat(c.filename()).isEqualTo("bao cao");
    assertThat(c.includeDeleted()).isFalse();
    assertThat(c.range().to()).isAfter(c.range().from());
  }

  @Test
  void should_returnStats_when_gettingStatsPath() throws Exception {
    when(statsService.stats(any(), eq("CAT")))
        .thenReturn(
            new FileStats(
                3,
                157,
                List.of(new FileStats.Bucket("CLEAN", 3, 157)),
                List.of(new FileStats.Bucket("CAT", 3, 157)),
                List.of(new FileStats.Bucket("text/csv", 3, 157)),
                List.of(new FileStats.DailyBucket(LocalDate.parse("2026-11-24"), 3, 157))));

    mockMvc
        .perform(
            get("/api/admin/files/stats")
                .param("categoryCode", "CAT")
                .param("from", "2026-11-01T00:00:00Z")
                .param("to", "2026-11-25T00:00:00Z")
                .with(viewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalFiles").value(3))
        .andExpect(jsonPath("$.totalBytes").value(157))
        .andExpect(jsonPath("$.byMimeType[0].key").value("text/csv"))
        .andExpect(jsonPath("$.daily[0].date").value("2026-11-24"));

    ArgumentCaptor<TimeRange> range = ArgumentCaptor.forClass(TimeRange.class);
    verify(statsService).stats(range.capture(), eq("CAT"));
    assertThat(range.getValue().from()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    verify(queryService, never()).get(any());
  }

  @Test
  void should_return400_when_statsRangeTooLong() throws Exception {
    mockMvc
        .perform(
            get("/api/admin/files/stats")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-11-01T00:00:00Z")
                .with(viewer()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return404_when_fileUnknown() throws Exception {
    UUID id = UUID.randomUUID();
    when(queryService.get(id)).thenThrow(new FileAssetNotFoundException(id));
    mockMvc.perform(get("/api/admin/files/" + id).with(viewer())).andExpect(status().isNotFound());
  }

  @Test
  void should_includeEffectiveVirusScan_when_gettingDetail() throws Exception {
    UUID id = UUID.randomUUID();
    when(queryService.get(id)).thenReturn(view(id));
    FileCategory category =
        new FileCategory("CAT", "b", List.of("text/csv"), 10L, true, false, null, null);
    when(categoryAdminService.get("CAT")).thenReturn(category);
    when(categoryAdminService.effectiveVirusScan(category)).thenReturn(true);

    mockMvc
        .perform(get("/api/admin/files/" + id).with(viewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.effectiveVirusScan").value(true))
        .andExpect(jsonPath("$.objectKey").doesNotExist());
  }
}
