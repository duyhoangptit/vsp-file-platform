package com.company.platform.shared.auth;

import java.util.Optional;
import java.util.UUID;

/**
 * Business module implement interface này để expose "ai là owner của resource" cho
 * ResourceAuthorizationService — tránh shared phải phụ thuộc ngược vào package của module đó.
 */
public interface ResourceOwnerLookup {

  ResourceType supports();

  Optional<UUID> findOwnerId(String resourceId);
}
