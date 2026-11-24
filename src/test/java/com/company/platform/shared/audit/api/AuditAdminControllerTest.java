package com.company.platform.shared.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.shared.audit.AuditAdminQueryService;
import com.company.platform.shared.audit.AuditEntryView;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.ConfigChangeItem;
import com.company.platform.shared.audit.DownloadChannel;
import com.company.platform.shared.audit.DownloadHistoryItem;
import com.company.platform.shared.audit.DownloadQuery;
import com.company.platform.shared.audit.FileAssetSummary;
import com.company.platform.shared.pagination.PageSlice;
import com.company.platform.shared.security.AdminActor;
import com.company.platform.shared.security.FilePermissions;
import com.company.platform.shared.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

@WebMvcTest(AuditAdminController.class)
@Import({SecurityConfig.class, AdminActor.class})
class AuditAdminControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuditAdminQueryService queryService;

  private static RequestPostProcessor historyViewer() {
    return jwt()
        .jwt(j -> j.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority(FilePermissions.HISTORY_VIEW));
  }

  private static DownloadHistoryItem item(FileAssetSummary file) {
    return new DownloadHistoryItem(
        UUID.randomUUID(),
        Instant.parse("2026-11-20T03:00:00Z"),
        UUID.randomUUID(),
        file == null ? UUID.randomUUID() : file.id(),
        "FILE_DOWNLOAD_URL_ISSUED",
        DownloadChannel.PRESIGNED,
        AuditResult.SUCCESS,
        Map.of("via", "FILE_API"),
        file);
  }

  @Test
  void should_return400_when_cursorIsGarbage() throws Exception {
    mockMvc
        .perform(get("/api/admin/file-downloads").param("cursor", "!!!").with(historyViewer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
    verify(queryService, never()).downloads(any(), any(), anyInt());
  }

  @Test
  void should_return400_when_channelUnknown() throws Exception {
    mockMvc
        .perform(get("/api/admin/file-downloads").param("channel", "FOO").with(historyViewer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void should_return400_when_rangeTooLong() throws Exception {
    mockMvc
        .perform(
            get("/api/admin/config-changes")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-11-01T00:00:00Z")
                .with(historyViewer()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_includeFileSummaryOrNull_when_listingDownloads() throws Exception {
    FileAssetSummary summary =
        new FileAssetSummary(UUID.randomUUID(), "a.csv", "text/csv", 42, "CAT");
    when(queryService.downloads(any(), isNull(), eq(50)))
        .thenReturn(new PageSlice<>(List.of(item(summary), item(null)), null));

    mockMvc
        .perform(
            get("/api/admin/file-downloads")
                .param("channel", "PRESIGNED")
                .param("result", "SUCCESS")
                .with(historyViewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].originalFilename").value("a.csv"))
        .andExpect(jsonPath("$.items[0].sizeBytes").value(42))
        .andExpect(jsonPath("$.items[0].channel").value("PRESIGNED"))
        .andExpect(jsonPath("$.items[1].originalFilename").doesNotExist())
        .andExpect(jsonPath("$.nextCursor").doesNotExist());

    ArgumentCaptor<DownloadQuery> q = ArgumentCaptor.forClass(DownloadQuery.class);
    verify(queryService).downloads(q.capture(), isNull(), eq(50));
    assertThat(q.getValue().channel()).isEqualTo(DownloadChannel.PRESIGNED);
    assertThat(q.getValue().result()).isEqualTo(AuditResult.SUCCESS);
  }

  @Test
  void should_returnFileHistory_when_requested() throws Exception {
    UUID fileId = UUID.randomUUID();
    when(queryService.forFile(eq(fileId), isNull(), eq(50)))
        .thenReturn(
            new PageSlice<>(
                List.of(
                    new AuditEntryView(
                        UUID.randomUUID(),
                        Instant.now(),
                        null,
                        "READ",
                        "FILE_ASSET",
                        fileId,
                        AuditResult.DENIED,
                        null)),
                "c"));

    mockMvc
        .perform(get("/api/admin/files/" + fileId + "/history").with(historyViewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].action").value("READ"))
        .andExpect(jsonPath("$.items[0].result").value("DENIED"))
        .andExpect(jsonPath("$.nextCursor").value("c"));
  }

  @Test
  void should_passResourceKey_when_listingConfigChanges() throws Exception {
    when(queryService.configChanges(any(), isNull(), eq("AVATAR"), isNull(), eq(50)))
        .thenReturn(
            new PageSlice<>(
                List.of(
                    new ConfigChangeItem(
                        UUID.randomUUID(),
                        Instant.now(),
                        UUID.randomUUID(),
                        "FILE_CATEGORY_UPDATED",
                        "AVATAR",
                        Map.of("changes", Map.of()))),
                null));

    mockMvc
        .perform(
            get("/api/admin/config-changes").param("resourceKey", "AVATAR").with(historyViewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].resourceKey").value("AVATAR"))
        .andExpect(jsonPath("$.items[0].action").value("FILE_CATEGORY_UPDATED"));
  }
}
