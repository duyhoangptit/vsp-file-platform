package com.company.platform.shared.auth;

import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Ghi audit_logs cho MỌI implementation của ResourceAuthorizationService, kể cả DENIED — bám vào
 * execution của method trên interface (thay vì annotation trên impl) để 1 implementation mới không
 * cần tự nhớ gọi AuditLogService, tránh lặp lại rủi ro "quên ghi log" từng có ở
 * DefaultResourceAuthorizationService.
 */
@Aspect
@Component
public class ResourceAccessAuditAspect {

  private final AuditLogService auditLogService;

  public ResourceAccessAuditAspect(AuditLogService auditLogService) {
    this.auditLogService = auditLogService;
  }

  @Around(
      "execution(* com.company.platform.shared.auth.ResourceAuthorizationService.canAccess(..)) "
          + "&& args(user, type, resourceId, action)")
  public Object auditAccessDecision(
      ProceedingJoinPoint joinPoint,
      UserContext user,
      ResourceType type,
      String resourceId,
      Action action)
      throws Throwable {
    boolean allowed = (boolean) joinPoint.proceed();
    UUID resourceUuid = parseResourceId(resourceId);
    if (resourceUuid != null) {
      auditLogService.record(
          user.userId(),
          action.name(),
          type.name(),
          resourceUuid,
          allowed ? AuditResult.SUCCESS : AuditResult.DENIED);
    }
    return allowed;
  }

  private UUID parseResourceId(String resourceId) {
    try {
      return UUID.fromString(resourceId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
