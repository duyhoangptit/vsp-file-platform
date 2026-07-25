package com.company.platform.shared.audit;

import java.util.UUID;
import org.springframework.stereotype.Service;

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
}
