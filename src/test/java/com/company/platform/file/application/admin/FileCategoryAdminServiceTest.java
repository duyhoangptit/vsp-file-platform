package com.company.platform.file.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.StaleVersionException;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResourceIds;
import com.company.platform.shared.audit.AuditResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FileCategoryAdminServiceTest {

  private final FileCategoryRepository repository = mock(FileCategoryRepository.class);
  private final AuditLogService auditLogService = mock(AuditLogService.class);
  private final VirusScanSettingsService virusScanSettingsService =
      mock(VirusScanSettingsService.class);
  private final FileCategoryAdminService service =
      new FileCategoryAdminService(repository, auditLogService, virusScanSettingsService);
  private final UUID actor = UUID.randomUUID();
  private FileCategory category;

  @BeforeEach
  void setUp() {
    category =
        new FileCategory("AVATAR", "avatars", List.of("image/png"), 1000L, false, true, 30, null);
    when(repository.findById("AVATAR")).thenReturn(Optional.of(category));
    when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private static UpdateFileCategoryCommand cmd(
      Boolean scan, List<String> mimes, Long maxSize, Integer retention, boolean retentionPresent) {
    return new UpdateFileCategoryCommand(0L, scan, mimes, maxSize, retention, retentionPresent);
  }

  @Test
  void should_rejectWildcardMime_when_updating() {
    assertThatThrownBy(
            () -> service.update("AVATAR", cmd(null, List.of("image/*"), null, null, false), actor))
        .isInstanceOf(FileValidationException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void should_rejectEmptyMimeList_when_updating() {
    assertThatThrownBy(
            () -> service.update("AVATAR", cmd(null, List.of(), null, null, false), actor))
        .isInstanceOf(FileValidationException.class);
  }

  @Test
  void should_rejectNonPositiveMaxSize_when_updating() {
    assertThatThrownBy(() -> service.update("AVATAR", cmd(null, null, 0L, null, false), actor))
        .isInstanceOf(FileValidationException.class);
  }

  @Test
  void should_rejectNonPositiveRetention_when_updating() {
    assertThatThrownBy(() -> service.update("AVATAR", cmd(null, null, null, 0, true), actor))
        .isInstanceOf(FileValidationException.class);
  }

  @Test
  void should_throwStaleVersion_when_versionMismatch() {
    UpdateFileCategoryCommand stale =
        new UpdateFileCategoryCommand(5L, true, null, null, null, false);
    assertThatThrownBy(() -> service.update("AVATAR", stale, actor))
        .isInstanceOf(StaleVersionException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void should_notAudit_when_noFieldChanges() {
    service.update("AVATAR", cmd(false, List.of("image/png"), 1000L, 30, true), actor);
    verify(auditLogService, never())
        .record(any(), any(), any(), any(), anyMap(), any(AuditResult.class));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_auditChangesOnly_when_someFieldsChange() {
    service.update("AVATAR", cmd(true, List.of("image/png"), 2000L, null, true), actor);

    ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
    verify(auditLogService)
        .record(
            eq(actor),
            eq("FILE_CATEGORY_UPDATED"),
            eq("FILE_CATEGORY"),
            eq(AuditResourceIds.forCategory("AVATAR")),
            metadata.capture(),
            eq(AuditResult.SUCCESS));
    assertThat(metadata.getValue()).containsEntry("resourceKey", "AVATAR");
    Map<String, Object> changes = (Map<String, Object>) metadata.getValue().get("changes");
    assertThat(changes).containsOnlyKeys("requireVirusScan", "maxSizeBytes", "retentionDays");
    assertThat((Map<String, Object>) changes.get("retentionDays"))
        .containsEntry("old", 30)
        .containsEntry("new", null);
    assertThat(category.isRequireVirusScan()).isTrue();
    assertThat(category.getRetentionDays()).isNull();
    assertThat(category.getUpdatedBy()).isEqualTo(actor);
  }
}
