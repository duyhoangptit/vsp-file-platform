package com.company.platform.file.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.application.admin.CategoryScanState;
import com.company.platform.file.application.admin.FileCategoryAdminService;
import com.company.platform.file.application.admin.UpdateFileCategoryCommand;
import com.company.platform.file.application.admin.VirusScanOverview;
import com.company.platform.file.application.admin.VirusScanSettingsService;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.StaleVersionException;
import com.company.platform.shared.security.AdminActor;
import com.company.platform.shared.security.FilePermissions;
import com.company.platform.shared.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(FileConfigAdminController.class)
@Import({SecurityConfig.class, AdminActor.class})
class FileConfigAdminControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private VirusScanSettingsService virusScanSettingsService;
  @MockitoBean private FileCategoryAdminService fileCategoryAdminService;

  private static final UUID ACTOR = UUID.randomUUID();

  private static RequestPostProcessor admin() {
    return jwt()
        .jwt(j -> j.subject(ACTOR.toString()))
        .authorities(
            FilePermissions.ALL.stream()
                .map(SimpleGrantedAuthority::new)
                .toArray(SimpleGrantedAuthority[]::new));
  }

  private static VirusScanOverview overview(boolean global, long version) {
    return new VirusScanOverview(
        global,
        version,
        ACTOR,
        Instant.now(),
        false,
        List.of(new CategoryScanState("A", true, global)));
  }

  @Test
  void should_returnOverview_when_gettingVirusScan() throws Exception {
    when(virusScanSettingsService.overview()).thenReturn(overview(false, 3));
    mockMvc
        .perform(get("/api/admin/virus-scan").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.globalEnabled").value(false))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.engineIntegrated").value(false))
        .andExpect(jsonPath("$.categories[0].code").value("A"))
        .andExpect(jsonPath("$.categories[0].effective").value(false));
  }

  @Test
  void should_passActorAndVersion_when_updatingVirusScan() throws Exception {
    when(virusScanSettingsService.updateGlobal(true, 3L, ACTOR)).thenReturn(overview(true, 4));
    mockMvc
        .perform(
            put("/api/admin/virus-scan")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.globalEnabled").value(true));
  }

  @Test
  void should_return409_when_virusScanVersionStale() throws Exception {
    when(virusScanSettingsService.updateGlobal(anyBoolean(), anyLong(), any()))
        .thenThrow(new StaleVersionException("Cấu hình virus scan", 1, 2));
    mockMvc
        .perform(
            put("/api/admin/virus-scan")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void should_return400_when_virusScanBodyMissingVersion() throws Exception {
    mockMvc
        .perform(
            put("/api/admin/virus-scan")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isBadRequest());
    verify(virusScanSettingsService, never()).updateGlobal(anyBoolean(), anyLong(), any());
  }

  @Test
  void should_return400_when_patchHasWildcardMime() throws Exception {
    when(fileCategoryAdminService.update(eq("A"), any(), any()))
        .thenThrow(new FileValidationException("Mime type không hợp lệ"));
    mockMvc
        .perform(
            patch("/api/admin/file-categories/A")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"allowedMimeTypes\":[\"image/*\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void should_distinguishExplicitNullRetention_when_patching() throws Exception {
    FileCategory category =
        new FileCategory("A", "b", List.of("text/csv"), 10L, false, false, null, null);
    when(fileCategoryAdminService.update(eq("A"), any(), eq(ACTOR))).thenReturn(category);

    mockMvc
        .perform(
            patch("/api/admin/file-categories/A")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"retentionDays\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("A"));

    ArgumentCaptor<UpdateFileCategoryCommand> cmd =
        ArgumentCaptor.forClass(UpdateFileCategoryCommand.class);
    verify(fileCategoryAdminService).update(eq("A"), cmd.capture(), eq(ACTOR));
    assertThat(cmd.getValue().retentionDaysPresent()).isTrue();
    assertThat(cmd.getValue().retentionDays()).isNull();
    assertThat(cmd.getValue().requireVirusScan()).isNull();
  }

  @Test
  void should_treatMissingRetentionAsUnchanged_when_patching() throws Exception {
    FileCategory category =
        new FileCategory("A", "b", List.of("text/csv"), 10L, true, false, 5, null);
    when(fileCategoryAdminService.update(eq("A"), any(), eq(ACTOR))).thenReturn(category);

    mockMvc
        .perform(
            patch("/api/admin/file-categories/A")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2,\"requireVirusScan\":true}"))
        .andExpect(status().isOk());

    ArgumentCaptor<UpdateFileCategoryCommand> cmd =
        ArgumentCaptor.forClass(UpdateFileCategoryCommand.class);
    verify(fileCategoryAdminService).update(eq("A"), cmd.capture(), eq(ACTOR));
    assertThat(cmd.getValue().version()).isEqualTo(2L);
    assertThat(cmd.getValue().retentionDaysPresent()).isFalse();
    assertThat(cmd.getValue().requireVirusScan()).isTrue();
  }

  @Test
  void should_return400_when_patchMissingVersion() throws Exception {
    mockMvc
        .perform(
            patch("/api/admin/file-categories/A")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requireVirusScan\":true}"))
        .andExpect(status().isBadRequest());
    verify(fileCategoryAdminService, never()).update(any(), any(), any());
  }

  @Test
  void should_return400_when_patchFieldHasWrongType() throws Exception {
    mockMvc
        .perform(
            patch("/api/admin/file-categories/A")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"maxSizeBytes\":\"big\"}"))
        .andExpect(status().isBadRequest());
    verify(fileCategoryAdminService, never()).update(any(), any(), any());
  }

  @Test
  void should_listCategoriesWithEffectiveScan_when_gettingCategories() throws Exception {
    FileCategory category =
        new FileCategory("A", "bucket-a", List.of("text/csv"), 10L, true, false, 7, null);
    when(fileCategoryAdminService.list()).thenReturn(List.of(category));
    when(fileCategoryAdminService.effectiveVirusScan(category)).thenReturn(false);

    mockMvc
        .perform(get("/api/admin/file-categories").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("A"))
        .andExpect(jsonPath("$[0].bucketName").value("bucket-a"))
        .andExpect(jsonPath("$[0].requireVirusScan").value(true))
        .andExpect(jsonPath("$[0].effectiveVirusScan").value(false))
        .andExpect(jsonPath("$[0].retentionDays").value(7))
        .andExpect(jsonPath("$[0].version").value(0));
  }
}
