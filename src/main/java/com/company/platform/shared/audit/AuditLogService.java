package com.company.platform.shared.audit;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;

  public AuditLogService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
  }

  /**
   * REQUIRES_NEW: audit_logs là append-only evidence của 1 quyết định (kể cả DENIED) — nó phải tồn
   * tại độc lập với việc phần business logic gọi nó có rollback hay không. Nếu chạy trong
   * transaction của caller (VD 1 method @Transactional throw AccessDeniedException ngay sau khi gọi
   * record(...) để từ chối), Spring sẽ đánh dấu transaction đó rollback-only vì exception
   * unchecked, kéo theo dòng audit DENIED vừa ghi cũng bị rollback theo — mất bằng chứng cho đúng
   * nhánh cần audit nhất. REQUIRES_NEW mở 1 transaction riêng, commit ngay khi record() trả về, nên
   * audit luôn persist bất kể caller sau đó có throw/rollback hay không.
   *
   * <p>Không đổi hành vi của caller hiện có ({@code ResourceAccessAuditAspect}, {@code
   * AuditedTransitionAspect}): cả 2 gọi record() khi bản thân chưa nằm trong transaction nào (bám
   * vào canAccess()/CAS-transition method không @Transactional), nên trước đây record() luôn tự mở
   * transaction mới của riêng nó — REQUIRES_NEW chỉ làm tường minh hành vi đó, không thay đổi gì
   * cho 2 caller này. Nơi thực sự cần REQUIRES_NEW là caller có @Transactional bao ngoài, ví dụ
   * {@code FileTemplateService.requireAdmin} (gọi record() rồi throw AccessDeniedException) — xem
   * comment ở đó.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      UUID actorId, String action, String resourceType, UUID resourceId, AuditResult result) {
    auditLogRepository.save(new AuditLog(actorId, action, resourceType, resourceId, null, result));
  }
}
