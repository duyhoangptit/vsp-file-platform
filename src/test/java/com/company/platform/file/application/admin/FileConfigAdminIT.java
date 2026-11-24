package com.company.platform.file.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FilePlatformSetting;
import com.company.platform.file.domain.FilePlatformSettingRepository;
import com.company.platform.file.domain.StaleVersionException;
import com.company.platform.shared.audit.AuditLog;
import com.company.platform.shared.audit.AuditLogRepository;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResourceIds;
import com.company.platform.shared.audit.AuditResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class FileConfigAdminIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired VirusScanSettingsService virusScanSettingsService;
  @Autowired FileCategoryAdminService fileCategoryAdminService;
  @Autowired FileCategoryRepository categoryRepository;
  @Autowired FilePlatformSettingRepository settingRepository;
  @Autowired AuditLogRepository auditLogRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @MockitoSpyBean AuditLogService auditLogService;

  private static final UUID SETTING_RESOURCE =
      AuditResourceIds.forSetting(FilePlatformSetting.VIRUS_SCAN_ENABLED);

  @AfterEach
  void resetGlobal() {
    reset(auditLogService);
    jdbcTemplate.update(
        "UPDATE file_platform_settings SET setting_value = 'false'::jsonb, version = 0"
            + " WHERE setting_key = 'virus_scan.enabled'");
    jdbcTemplate.update("DELETE FROM audit_logs");
  }

  private List<AuditLog> settingAudits() {
    return auditLogRepository.findByResourceId(SETTING_RESOURCE);
  }

  @Test
  void should_toggleGlobal_and_audit_when_versionMatches() {
    UUID actor = UUID.randomUUID();
    VirusScanOverview overview = virusScanSettingsService.updateGlobal(true, 0L, actor);

    assertThat(overview.globalEnabled()).isTrue();
    assertThat(overview.version()).isEqualTo(1L);
    assertThat(overview.updatedBy()).isEqualTo(actor);
    assertThat(overview.engineIntegrated()).isFalse();
    List<AuditLog> audits = settingAudits();
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0).getAction()).isEqualTo("VIRUS_SCAN_GLOBAL_UPDATED");
    assertThat(audits.get(0).getActorId()).isEqualTo(actor);
    assertThat(audits.get(0).getMetadata())
        .containsEntry("resourceKey", "virus_scan.enabled")
        .containsEntry("old", false)
        .containsEntry("new", true);
  }

  @Test
  void should_throwStaleAndNotAudit_when_staleVersion() {
    virusScanSettingsService.updateGlobal(true, 0L, UUID.randomUUID());
    assertThatThrownBy(() -> virusScanSettingsService.updateGlobal(false, 0L, UUID.randomUUID()))
        .isInstanceOf(StaleVersionException.class);
    assertThat(settingAudits()).hasSize(1);
    assertThat(virusScanSettingsService.isGloballyEnabled()).isTrue();
  }

  @Test
  void should_notAudit_when_globalValueUnchanged() {
    VirusScanOverview overview =
        virusScanSettingsService.updateGlobal(false, 0L, UUID.randomUUID());
    assertThat(overview.version()).isZero();
    assertThat(settingAudits()).isEmpty();
  }

  @Test
  void should_computeEffective_when_globalOffAndCategoryOn() {
    String code = "EFF_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(code, "eff-bucket", List.of("text/csv"), 10L, true, false, null, null));

    assertThat(stateOf(virusScanSettingsService.overview(), code).effective()).isFalse();
    virusScanSettingsService.updateGlobal(true, 0L, UUID.randomUUID());
    CategoryScanState on = stateOf(virusScanSettingsService.overview(), code);
    assertThat(on.requireVirusScan()).isTrue();
    assertThat(on.effective()).isTrue();
    assertThat(
            fileCategoryAdminService.effectiveVirusScan(
                categoryRepository.findById(code).orElseThrow()))
        .isTrue();
  }

  @Test
  void should_rollbackUpdate_when_auditWriteFails() {
    doAnswer(
            inv -> {
              inv.callRealMethod();
              throw new IllegalStateException("boom after audit");
            })
        .when(auditLogService)
        .record(
            any(), eq("VIRUS_SCAN_GLOBAL_UPDATED"), any(), any(), anyMap(), any(AuditResult.class));

    assertThatThrownBy(() -> virusScanSettingsService.updateGlobal(true, 0L, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(virusScanSettingsService.isGloballyEnabled()).isFalse();
    assertThat(settingAudits()).isEmpty();
  }

  @Test
  void should_persistCategoryUpdate_and_bumpVersion_when_valid() {
    String code = "UPD_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(code, "upd-bucket", List.of("text/csv"), 10L, false, false, 7, null));
    UUID actor = UUID.randomUUID();

    FileCategory updated =
        fileCategoryAdminService.update(
            code,
            new UpdateFileCategoryCommand(
                0L, true, List.of("text/csv", "text/plain"), 20L, null, true),
            actor);

    assertThat(updated.getVersion()).isEqualTo(1L);
    FileCategory reloaded = categoryRepository.findById(code).orElseThrow();
    assertThat(reloaded.isRequireVirusScan()).isTrue();
    assertThat(reloaded.getAllowedMimeTypes()).containsExactly("text/csv", "text/plain");
    assertThat(reloaded.getMaxSizeBytes()).isEqualTo(20L);
    assertThat(reloaded.getRetentionDays()).isNull();
    assertThat(auditLogRepository.findByResourceId(AuditResourceIds.forCategory(code))).hasSize(1);

    assertThatThrownBy(
            () ->
                fileCategoryAdminService.update(
                    code, new UpdateFileCategoryCommand(0L, false, null, null, null, false), actor))
        .isInstanceOf(StaleVersionException.class);
  }

  private static CategoryScanState stateOf(VirusScanOverview overview, String code) {
    return overview.categories().stream()
        .filter(c -> c.code().equals(code))
        .findFirst()
        .orElseThrow();
  }
}
