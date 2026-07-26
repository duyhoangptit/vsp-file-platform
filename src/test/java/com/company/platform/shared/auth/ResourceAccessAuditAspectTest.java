package com.company.platform.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

class ResourceAccessAuditAspectTest {

  private final AuditLogService auditLogService = mock(AuditLogService.class);

  @Test
  void should_recordSuccess_when_targetAllowsAccessWithValidResourceId() {
    ResourceAuthorizationService proxied = proxiedServiceReturning(true);
    UUID userId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    UserContext user = new UserContext(userId, Set.of());

    boolean allowed =
        proxied.canAccess(user, ResourceType.FILE_ASSET, resourceId.toString(), Action.WRITE);

    assertThat(allowed).isTrue();
    verify(auditLogService)
        .record(eq(userId), eq("WRITE"), eq("FILE_ASSET"), eq(resourceId), eq(AuditResult.SUCCESS));
  }

  @Test
  void should_recordDenied_when_targetDeniesAccessWithValidResourceId() {
    ResourceAuthorizationService proxied = proxiedServiceReturning(false);
    UUID userId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    UserContext user = new UserContext(userId, Set.of());

    boolean allowed = proxied.canAccess(user, ResourceType.JOB, resourceId.toString(), Action.READ);

    assertThat(allowed).isFalse();
    verify(auditLogService)
        .record(eq(userId), eq("READ"), eq("JOB"), eq(resourceId), eq(AuditResult.DENIED));
  }

  @Test
  void should_skipAudit_when_resourceIdIsNotAValidUuid() {
    ResourceAuthorizationService proxied = proxiedServiceReturning(true);
    UserContext user = new UserContext(UUID.randomUUID(), Set.of());

    boolean allowed =
        proxied.canAccess(user, ResourceType.FILE_ASSET, "EXPORT_USER_CSV", Action.WRITE);

    assertThat(allowed).isTrue();
    verifyNoInteractions(auditLogService);
  }

  private ResourceAuthorizationService proxiedServiceReturning(boolean allowed) {
    ResourceAuthorizationService target = (user, type, resourceId, action) -> allowed;
    AspectJProxyFactory factory = new AspectJProxyFactory(target);
    factory.addAspect(new ResourceAccessAuditAspect(auditLogService));
    return factory.getProxy();
  }
}
