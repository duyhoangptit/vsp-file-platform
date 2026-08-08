package com.company.platform.shared.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Bảo vệ mọi endpoint dưới prefix {@code /api/internal/**} (đăng ký path pattern ở
 * InternalApiWebConfig) bằng 1 shared-secret header — dành cho service-to-service call, không gắn
 * với 1 end-user cụ thể nên không dùng {@link RequiresAccess}/{@link ResourceAuthorizationService}.
 * Chạy như HandlerInterceptor (không phải servlet Filter) để exception ném ra được
 * DispatcherServlet resolve qua {@link AuthExceptionHandler} như mọi exception khác trong app, thay
 * vì phải tự viết response ở tầng Filter.
 *
 * <p>Nếu {@code app.internal-api.key} chưa được cấu hình (rỗng) thì từ chối MỌI request (fail
 * closed) thay vì so khớp "" == "" — tránh trường hợp key rỗng vô tình cho phép bypass bằng cách
 * gửi header rỗng.
 */
@Component
@EnableConfigurationProperties(InternalApiKeyProperties.class)
public class InternalApiKeyInterceptor implements HandlerInterceptor {

  static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

  private final InternalApiKeyProperties properties;

  public InternalApiKeyInterceptor(InternalApiKeyProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String expectedKey = properties.key();
    String providedKey = request.getHeader(INTERNAL_API_KEY_HEADER);
    if (expectedKey == null
        || expectedKey.isBlank()
        || providedKey == null
        || !constantTimeEquals(providedKey, expectedKey)) {
      throw new UnauthenticatedException(
          "Thiếu hoặc sai header " + INTERNAL_API_KEY_HEADER + " cho internal API");
    }
    return true;
  }

  // So sánh constant-time để tránh timing attack dò key qua độ trễ response.
  private boolean constantTimeEquals(String provided, String expected) {
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }
}
