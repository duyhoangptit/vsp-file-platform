package com.company.platform.shared.config;

import com.company.platform.shared.auth.InternalApiKeyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Áp {@link InternalApiKeyInterceptor} cho mọi endpoint dưới {@code /api/internal/**} — 1 chỗ
 * đăng ký duy nhất, các endpoint internal thêm sau này không cần tự khai báo lại auth.
 */
@Configuration
public class InternalApiWebConfig implements WebMvcConfigurer {

  private final InternalApiKeyInterceptor internalApiKeyInterceptor;

  public InternalApiWebConfig(InternalApiKeyInterceptor internalApiKeyInterceptor) {
    this.internalApiKeyInterceptor = internalApiKeyInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(internalApiKeyInterceptor).addPathPatterns("/api/internal/**");
  }
}
