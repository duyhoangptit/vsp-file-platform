package com.company.platform.shared.auth;

public class AccessDeniedException extends RuntimeException {

  public AccessDeniedException(ResourceType type, String resourceId) {
    super("Không có quyền truy cập %s %s".formatted(type, resourceId));
  }
}
