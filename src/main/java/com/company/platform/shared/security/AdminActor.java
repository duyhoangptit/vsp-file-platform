package com.company.platform.shared.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Actor của request admin hiện tại, lấy từ {@code sub} (UUID) của JWT — admin API không dùng {@code
 * UserContext} (header). {@code sub} không phải UUID đã bị chặn 401 ở {@link SecurityConfig}. Trả
 * {@code null} khi security bị tắt (principal {@code local-dev}).
 */
@Component
public class AdminActor {

  public UUID currentId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwt) {
      return UUID.fromString(jwt.getToken().getSubject());
    }
    return null;
  }
}
