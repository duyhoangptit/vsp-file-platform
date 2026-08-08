package com.company.platform.template.application;

import com.company.platform.file.application.FileDownloadService;
import com.company.platform.file.application.FileUploadInitiation;
import com.company.platform.file.application.FileUploadService;
import com.company.platform.file.application.InitiateFileUploadCommand;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
import com.company.platform.shared.lock.AdvisoryLockService;
import com.company.platform.template.domain.FileTemplateRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý upload/download file template theo (category, templateCode), có version. Chỉ tái dùng
 * public API của module file (FileUploadService/FileDownloadService), không tự viết lại logic
 * presigned URL/verify — đúng nguyên tắc module boundary (mục 2 design doc gốc).
 *
 * <p>Quyền upload (initiate/confirm) yêu cầu role ADMIN, check ngay tại đây (application service
 * gọi trực tiếp từ Controller trong cùng request) — cùng pattern với {@code
 * ExportJobService#submitUserCsvExport}: role-check thuần, không owner-based nên không dùng được
 * {@code @RequiresAccess}/{@code ResourceOwnerLookup}. Khác với ExportJobService, chủ động ghi
 * audit_logs cho cả 2 nhánh ALLOW/DENIED (mục 9 design doc gốc).
 */
@Service
public class FileTemplateService {

  private static final String ROLE_ADMIN = "ADMIN";
  private static final String TEMPLATE_FILE_CATEGORY_CODE = "FILE_TEMPLATE";
  private static final String TEMPLATE_OWNER_TYPE = "FILE_TEMPLATE";
  private static final String AUDIT_RESOURCE_TYPE = "FILE_TEMPLATE";
  private static final String ACTION_UPLOAD_INITIATE = "TEMPLATE_UPLOAD_INITIATE";
  private static final int LOCK_MAX_ATTEMPTS = 20;
  private static final Duration LOCK_RETRY_DELAY = Duration.ofMillis(25);

  private final FileTemplateRepository fileTemplateRepository;
  private final FileUploadService fileUploadService;
  private final FileDownloadService fileDownloadService;
  private final AdvisoryLockService advisoryLockService;
  private final AuditLogService auditLogService;

  public FileTemplateService(
      FileTemplateRepository fileTemplateRepository,
      FileUploadService fileUploadService,
      FileDownloadService fileDownloadService,
      AdvisoryLockService advisoryLockService,
      AuditLogService auditLogService) {
    this.fileTemplateRepository = fileTemplateRepository;
    this.fileUploadService = fileUploadService;
    this.fileDownloadService = fileDownloadService;
    this.advisoryLockService = advisoryLockService;
    this.auditLogService = auditLogService;
  }

  @Transactional
  public TemplateUploadInitiation initiateUpload(
      UserContext user, InitiateTemplateUploadCommand command) {
    requireAdmin(user, ACTION_UPLOAD_INITIATE, command.category(), command.templateCode());

    UUID templateId = UUID.randomUUID();
    FileUploadInitiation initiation =
        fileUploadService.initiateUpload(
            new InitiateFileUploadCommand(
                TEMPLATE_FILE_CATEGORY_CODE,
                command.originalFilename(),
                command.mimeType(),
                command.sizeBytes(),
                TEMPLATE_OWNER_TYPE,
                templateId,
                user.userId()));

    return new TemplateUploadInitiation(
        initiation.fileAssetId(), initiation.uploadUrl(), initiation.expiresAt());
  }

  /**
   * Method này (và mọi method @Transactional gọi nó, VD initiateUpload ở trên, hoặc confirmUpload
   * sẽ thêm ở Task 4) throw AccessDeniedException ngay sau khi ghi audit DENIED — nếu
   * AuditLogService.record(...) chạy trong cùng transaction, exception unchecked đó sẽ đánh dấu
   * transaction rollback-only và dòng audit DENIED vừa ghi bị cuốn theo, không bao giờ tới được DB.
   * AuditLogService.record(...) đã được khai báo REQUIRES_NEW (xem comment ở đó) chính để giải
   * quyết việc này một cách chung — nó luôn commit độc lập với transaction của caller, nên
   * requireAdmin không cần (và không nên) tự tách transaction ở đây nữa.
   */
  private void requireAdmin(UserContext user, String action, String category, String templateCode) {
    boolean isAdmin = user.roles().contains(ROLE_ADMIN);
    auditLogService.record(
        user.userId(),
        action,
        AUDIT_RESOURCE_TYPE,
        pseudoResourceId(category, templateCode),
        isAdmin ? AuditResult.SUCCESS : AuditResult.DENIED);
    if (!isAdmin) {
      throw new AccessDeniedException(ResourceType.FILE_TEMPLATE, category + ":" + templateCode);
    }
  }

  // UUID ổn định theo (category, templateCode) dùng làm resourceId audit cho bước check quyền —
  // tại initiate chưa có file_template_manager.id thật (row chỉ tạo ở bước confirm, Task 4).
  private UUID pseudoResourceId(String category, String templateCode) {
    return UUID.nameUUIDFromBytes((category + ":" + templateCode).getBytes(StandardCharsets.UTF_8));
  }
}
