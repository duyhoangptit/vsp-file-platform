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

  public void record(
      UUID actorId, String action, String resourceType, UUID resourceId, AuditResult result) {
    auditLogRepository.save(new AuditLog(actorId, action, resourceType, resourceId, null, result));
  }

  /**
   * Giống {@link #record}, nhưng chạy trong 1 transaction riêng (REQUIRES_NEW), commit ngay khi trả
   * về — dành cho caller mà bản thân audit write phải sống sót độc lập với việc phần business logic
   * bao ngoài có rollback hay không (VD {@code FileTemplateService.requireAdmin}: ghi audit DENIED
   * rồi throw AccessDeniedException ngay trong 1 method @Transactional — nếu dùng {@link #record}
   * thường, exception unchecked sẽ đánh dấu transaction đó rollback-only và cuốn theo dòng audit
   * DENIED vừa ghi, mất bằng chứng cho đúng nhánh cần audit nhất).
   *
   * <p>Cố tình KHÔNG áp REQUIRES_NEW lên {@link #record} dùng chung, để không đổi hành vi
   * transaction hiện tại của 2 caller khác đang dùng nó ({@code ResourceAccessAuditAspect}, {@code
   * AuditedTransitionAspect} — cái sau chạy trong cùng transaction @Transactional của các method
   * CAS-transition ở {@code JobStatusService}, ví dụ tryMarkProcessing/markDone/markFailed). Với
   * {@code AuditedTransitionAspect}, audit chỉ nên trở thành durable khi transaction CAS bao ngoài
   * thật sự commit thành công — tách nó ra thành REQUIRES_NEW sẽ khiến audit có thể commit độc lập
   * trước cả khi biết outer transaction có commit được hay không (rủi ro hẹp nhưng có thật nếu
   * commit của outer transaction fail sau đó), nên giữ nguyên hành vi cũ cho những caller này. Chỉ
   * dùng {@link #recordIndependently} khi rõ ràng cần tính chất "audit luôn persist bất kể caller
   * rollback" như mô tả ở trên.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordIndependently(
      UUID actorId, String action, String resourceType, UUID resourceId, AuditResult result) {
    auditLogRepository.save(new AuditLog(actorId, action, resourceType, resourceId, null, result));
  }
}
