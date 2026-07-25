package com.company.platform.shared.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Service chưa tự làm authentication (chưa có Spring Security/JWT) — giả định có API
 * gateway/reverse proxy phía trước đã xác thực và gắn header tin cậy trước khi request tới đây. Khi
 * có gateway/OAuth2 thật, chỉ cần thay nội dung {@link #userContext(HttpServletRequest)}, phần còn
 * lại của app vẫn dùng UserContext như cũ.
 */
@Configuration
public class UserContextConfig {

  static final String USER_ID_HEADER = "X-User-Id";
  static final String USER_ROLES_HEADER = "X-User-Roles";

  @Bean
  @RequestScope
  public UserContext userContext(HttpServletRequest request) {
    String userIdHeader = request.getHeader(USER_ID_HEADER);
    if (userIdHeader == null || userIdHeader.isBlank()) {
      throw new UnauthenticatedException("Thiếu header " + USER_ID_HEADER);
    }

    UUID userId;
    try {
      userId = UUID.fromString(userIdHeader);
    } catch (IllegalArgumentException e) {
      throw new UnauthenticatedException(
          "Header " + USER_ID_HEADER + " không hợp lệ: " + userIdHeader);
    }

    String rolesHeader = request.getHeader(USER_ROLES_HEADER);
    Set<String> roles =
        rolesHeader == null || rolesHeader.isBlank()
            ? Set.of()
            : Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

    return new UserContext(userId, roles);
  }
}
