package com.company.platform.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultResourceAuthorizationServiceTest {

  private final AuditLogService auditLogService = mock(AuditLogService.class);

  @Test
  void should_allowAccess_when_userIsTheOwner() {
    UUID userId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    ResourceOwnerLookup lookup = fixedLookup(ResourceType.FILE_ASSET, Optional.of(userId));
    DefaultResourceAuthorizationService service =
        new DefaultResourceAuthorizationService(List.of(lookup), auditLogService);
    UserContext user = new UserContext(userId, Set.of());

    boolean allowed =
        service.canAccess(user, ResourceType.FILE_ASSET, resourceId.toString(), Action.WRITE);

    assertThat(allowed).isTrue();
    verify(auditLogService)
        .record(eq(userId), eq("WRITE"), eq("FILE_ASSET"), eq(resourceId), eq(AuditResult.SUCCESS));
  }

  @Test
  void should_denyAccess_when_userIsNotTheOwner() {
    UUID userId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    ResourceOwnerLookup lookup = fixedLookup(ResourceType.FILE_ASSET, Optional.of(ownerId));
    DefaultResourceAuthorizationService service =
        new DefaultResourceAuthorizationService(List.of(lookup), auditLogService);
    UserContext user = new UserContext(userId, Set.of());

    boolean allowed =
        service.canAccess(user, ResourceType.FILE_ASSET, resourceId.toString(), Action.WRITE);

    assertThat(allowed).isFalse();
    verify(auditLogService)
        .record(eq(userId), eq("WRITE"), eq("FILE_ASSET"), eq(resourceId), eq(AuditResult.DENIED));
  }

  @Test
  void should_allowAccess_when_userHasAdminRole_regardlessOfOwnership() {
    UUID userId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    ResourceOwnerLookup lookup = fixedLookup(ResourceType.FILE_ASSET, Optional.of(ownerId));
    DefaultResourceAuthorizationService service =
        new DefaultResourceAuthorizationService(List.of(lookup), auditLogService);
    UserContext admin = new UserContext(userId, Set.of("ADMIN"));

    boolean allowed =
        service.canAccess(admin, ResourceType.FILE_ASSET, resourceId.toString(), Action.WRITE);

    assertThat(allowed).isTrue();
  }

  @Test
  void should_denyAccess_when_resourceDoesNotExist() {
    UUID userId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    ResourceOwnerLookup lookup = fixedLookup(ResourceType.FILE_ASSET, Optional.empty());
    DefaultResourceAuthorizationService service =
        new DefaultResourceAuthorizationService(List.of(lookup), auditLogService);
    UserContext user = new UserContext(userId, Set.of());

    boolean allowed =
        service.canAccess(user, ResourceType.FILE_ASSET, resourceId.toString(), Action.WRITE);

    assertThat(allowed).isFalse();
  }

  @Test
  void should_denyAccess_when_noLookupRegisteredForResourceType() {
    UUID userId = UUID.randomUUID();
    DefaultResourceAuthorizationService service =
        new DefaultResourceAuthorizationService(List.of(), auditLogService);
    UserContext user = new UserContext(userId, Set.of());

    boolean allowed =
        service.canAccess(user, ResourceType.JOB, UUID.randomUUID().toString(), Action.READ);

    assertThat(allowed).isFalse();
  }

  private ResourceOwnerLookup fixedLookup(ResourceType type, Optional<UUID> ownerId) {
    ResourceOwnerLookup lookup = mock(ResourceOwnerLookup.class);
    when(lookup.supports()).thenReturn(type);
    when(lookup.findOwnerId(any())).thenReturn(ownerId);
    return lookup;
  }
}
