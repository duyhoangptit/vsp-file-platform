package com.company.platform.shared.auth;

import java.util.Set;
import java.util.UUID;

/**
 * Không dùng record vì đây là request-scoped bean — Spring cần CGLIB subclass nó để tạo scoped
 * proxy khi inject vào singleton bean (Controller/Service), mà record luôn là final class nên CGLIB
 * không subclass được.
 */
public class UserContext {

  private final UUID userId;
  private final Set<String> roles;

  public UserContext(UUID userId, Set<String> roles) {
    this.userId = userId;
    this.roles = roles;
  }

  public UUID userId() {
    return userId;
  }

  public Set<String> roles() {
    return roles;
  }
}
