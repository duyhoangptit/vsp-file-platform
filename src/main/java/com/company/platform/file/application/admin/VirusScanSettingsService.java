package com.company.platform.file.application.admin;

import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FilePlatformSetting;
import com.company.platform.file.domain.FilePlatformSettingRepository;
import com.company.platform.file.domain.StaleVersionException;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResourceIds;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.FileAuditActions;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Công tắc tổng virus scan + trạng thái "có hiệu lực" theo category ({@code effective = global &&
 * category.requireVirusScan}). Chỉ là cấu hình: luồng upload hiện chưa đọc cờ này.
 */
@Service
public class VirusScanSettingsService {

  static final boolean ENGINE_INTEGRATED = false;

  private final FilePlatformSettingRepository settingRepository;
  private final FileCategoryRepository categoryRepository;
  private final AuditLogService auditLogService;

  public VirusScanSettingsService(
      FilePlatformSettingRepository settingRepository,
      FileCategoryRepository categoryRepository,
      AuditLogService auditLogService) {
    this.settingRepository = settingRepository;
    this.categoryRepository = categoryRepository;
    this.auditLogService = auditLogService;
  }

  @Transactional(readOnly = true)
  public boolean isGloballyEnabled() {
    return loadSetting().booleanValue();
  }

  public boolean effective(FileCategory category) {
    return category.isRequireVirusScan() && isGloballyEnabled();
  }

  @Transactional(readOnly = true)
  public VirusScanOverview overview() {
    return toOverview(loadSetting());
  }

  @Transactional
  public VirusScanOverview updateGlobal(boolean enabled, long expectedVersion, UUID actorId) {
    FilePlatformSetting setting = loadSetting();
    if (setting.getVersion() != expectedVersion) {
      throw new StaleVersionException("Cấu hình virus scan", expectedVersion, setting.getVersion());
    }
    boolean old = setting.booleanValue();
    if (old == enabled) {
      return toOverview(setting);
    }
    setting.update(enabled, actorId);
    FilePlatformSetting saved = settingRepository.saveAndFlush(setting);
    auditLogService.record(
        actorId,
        FileAuditActions.VIRUS_SCAN_GLOBAL_UPDATED,
        FileAuditActions.RESOURCE_FILE_PLATFORM_SETTING,
        AuditResourceIds.forSetting(FilePlatformSetting.VIRUS_SCAN_ENABLED),
        Map.of("resourceKey", FilePlatformSetting.VIRUS_SCAN_ENABLED, "old", old, "new", enabled),
        AuditResult.SUCCESS);
    return toOverview(saved);
  }

  private FilePlatformSetting loadSetting() {
    return settingRepository
        .findById(FilePlatformSetting.VIRUS_SCAN_ENABLED)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Thiếu setting "
                        + FilePlatformSetting.VIRUS_SCAN_ENABLED
                        + " (changeset 012)"));
  }

  private VirusScanOverview toOverview(FilePlatformSetting setting) {
    boolean global = setting.booleanValue();
    return new VirusScanOverview(
        global,
        setting.getVersion(),
        setting.getUpdatedBy(),
        setting.getUpdatedAt(),
        ENGINE_INTEGRATED,
        categoryRepository.findAll(Sort.by("code")).stream()
            .map(
                c ->
                    new CategoryScanState(
                        c.getCode(), c.isRequireVirusScan(), global && c.isRequireVirusScan()))
            .toList());
  }
}
