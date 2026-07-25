package com.company.platform.shared.auth;

import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DefaultResourceAuthorizationService implements ResourceAuthorizationService {

  private static final String ROLE_ADMIN = "ADMIN";

  private final Map<ResourceType, ResourceOwnerLookup> lookupsByType;
  private final AuditLogService auditLogService;

  public DefaultResourceAuthorizationService(
      List<ResourceOwnerLookup> lookups, AuditLogService auditLogService) {
    this.lookupsByType =
        lookups.stream()
            .collect(Collectors.toMap(ResourceOwnerLookup::supports, Function.identity()));
    this.auditLogService = auditLogService;
  }

  @Override
  public boolean canAccess(UserContext user, ResourceType type, String resourceId, Action action) {
    boolean allowed = evaluate(user, type, resourceId);
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

  private boolean evaluate(UserContext user, ResourceType type, String resourceId) {
    if (user.roles().contains(ROLE_ADMIN)) {
      return true;
    }
    ResourceOwnerLookup lookup = lookupsByType.get(type);
    if (lookup == null) {
      return false;
    }
    return lookup
        .findOwnerId(resourceId)
        .map(ownerId -> ownerId.equals(user.userId()))
        .orElse(false);
  }

  private UUID parseResourceId(String resourceId) {
    try {
      return UUID.fromString(resourceId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
