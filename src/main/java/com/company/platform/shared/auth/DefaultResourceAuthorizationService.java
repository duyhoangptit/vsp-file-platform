package com.company.platform.shared.auth;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DefaultResourceAuthorizationService implements ResourceAuthorizationService {

  private static final String ROLE_ADMIN = "ADMIN";

  private final Map<ResourceType, ResourceOwnerLookup> lookupsByType;

  public DefaultResourceAuthorizationService(List<ResourceOwnerLookup> lookups) {
    this.lookupsByType =
        lookups.stream()
            .collect(Collectors.toMap(ResourceOwnerLookup::supports, Function.identity()));
  }

  @Override
  public boolean canAccess(UserContext user, ResourceType type, String resourceId, Action action) {
    return evaluate(user, type, resourceId);
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
}
