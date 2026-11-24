package com.company.platform.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled false = admin API cấp mọi quyền {@code file.*} cho principal {@code local-dev}
 *     (chỉ dùng local; profile {@code prod} sẽ fail lúc khởi động)
 * @param issuer {@code iss} kỳ vọng (tuỳ chọn); rỗng = chỉ verify chữ ký + hạn
 */
@ConfigurationProperties("app.admin-security")
public record AdminSecurityProperties(boolean enabled, String issuer) {

  public AdminSecurityProperties {
    issuer = issuer == null ? "" : issuer;
  }
}
