package com.company.platform.shared.auth;

public interface ResourceAuthorizationService {

  boolean canAccess(UserContext user, ResourceType type, String resourceId, Action action);
}
