package com.company.platform.file.application.admin;

import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryNotFoundException;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.StaleVersionException;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResourceIds;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.FileAuditActions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin xem/sửa file category. Chỉ sửa được {@code requireVirusScan}, {@code allowedMimeTypes},
 * {@code maxSizeBytes}, {@code retentionDays}; thay đổi chỉ áp cho upload mới.
 */
@Service
public class FileCategoryAdminService {

  // type/subtype theo RFC 6838 (chữ thường), KHÔNG cho wildcard: FileUploadService so khớp chính
  // xác bằng contains().
  private static final Pattern MIME =
      Pattern.compile("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$");

  private final FileCategoryRepository repository;
  private final AuditLogService auditLogService;
  private final VirusScanSettingsService virusScanSettingsService;

  public FileCategoryAdminService(
      FileCategoryRepository repository,
      AuditLogService auditLogService,
      VirusScanSettingsService virusScanSettingsService) {
    this.repository = repository;
    this.auditLogService = auditLogService;
    this.virusScanSettingsService = virusScanSettingsService;
  }

  @Transactional(readOnly = true)
  public List<FileCategory> list() {
    return repository.findAll(Sort.by("code"));
  }

  @Transactional(readOnly = true)
  public FileCategory get(String code) {
    return repository.findById(code).orElseThrow(() -> new FileCategoryNotFoundException(code));
  }

  public boolean effectiveVirusScan(FileCategory category) {
    return virusScanSettingsService.effective(category);
  }

  @Transactional
  public FileCategory update(String code, UpdateFileCategoryCommand cmd, UUID actorId) {
    FileCategory category = get(code);
    if (category.getVersion() != cmd.version()) {
      throw new StaleVersionException(
          "File category " + code, cmd.version(), category.getVersion());
    }
    validate(cmd);

    Map<String, Object> changes = new LinkedHashMap<>();
    diff(changes, "requireVirusScan", category.isRequireVirusScan(), cmd.requireVirusScan());
    diff(changes, "allowedMimeTypes", category.getAllowedMimeTypes(), cmd.allowedMimeTypes());
    diff(changes, "maxSizeBytes", category.getMaxSizeBytes(), cmd.maxSizeBytes());
    if (cmd.retentionDaysPresent()
        && !Objects.equals(category.getRetentionDays(), cmd.retentionDays())) {
      changes.put("retentionDays", change(category.getRetentionDays(), cmd.retentionDays()));
    }
    if (changes.isEmpty()) {
      return category;
    }

    category.applyAdminUpdate(
        cmd.requireVirusScan(),
        cmd.allowedMimeTypes(),
        cmd.maxSizeBytes(),
        cmd.retentionDays(),
        cmd.retentionDaysPresent() && cmd.retentionDays() == null,
        actorId);
    FileCategory saved = repository.saveAndFlush(category);
    auditLogService.record(
        actorId,
        FileAuditActions.FILE_CATEGORY_UPDATED,
        FileAuditActions.RESOURCE_FILE_CATEGORY,
        AuditResourceIds.forCategory(code),
        Map.of("resourceKey", code, "changes", changes),
        AuditResult.SUCCESS);
    return saved;
  }

  private static void validate(UpdateFileCategoryCommand cmd) {
    if (cmd.allowedMimeTypes() != null) {
      if (cmd.allowedMimeTypes().isEmpty()) {
        throw new FileValidationException("allowedMimeTypes không được rỗng");
      }
      for (String mime : cmd.allowedMimeTypes()) {
        if (mime == null || !MIME.matcher(mime).matches()) {
          throw new FileValidationException(
              "Mime type không hợp lệ (dạng type/subtype, không wildcard): " + mime);
        }
      }
    }
    if (cmd.maxSizeBytes() != null && cmd.maxSizeBytes() <= 0) {
      throw new FileValidationException("maxSizeBytes phải > 0");
    }
    if (cmd.retentionDays() != null && cmd.retentionDays() <= 0) {
      throw new FileValidationException("retentionDays phải > 0 hoặc null");
    }
  }

  private static void diff(Map<String, Object> changes, String field, Object current, Object next) {
    if (next != null && !Objects.equals(current, next)) {
      changes.put(field, change(current, next));
    }
  }

  private static Map<String, Object> change(Object old, Object next) {
    Map<String, Object> change = new HashMap<>();
    change.put("old", old);
    change.put("new", next);
    return change;
  }
}
