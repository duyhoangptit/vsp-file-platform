package com.company.platform.shared.audit;

import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuditedTransitionAspect {

  private final AuditLogService auditLogService;

  public AuditedTransitionAspect(AuditLogService auditLogService) {
    this.auditLogService = auditLogService;
  }

  @AfterReturning(pointcut = "@annotation(audited)", returning = "returnValue")
  public void recordAudit(JoinPoint joinPoint, Audited audited, Object returnValue) {
    if (returnValue instanceof Boolean success && !success) {
      return;
    }
    Object[] args = joinPoint.getArgs();
    if (args.length == 0 || !(args[0] instanceof UUID resourceId)) {
      throw new IllegalStateException(
          "@Audited yêu cầu tham số đầu tiên của method là UUID resourceId: "
              + joinPoint.getSignature());
    }
    auditLogService.record(
        null, audited.action(), audited.resourceType(), resourceId, audited.result());
  }
}
