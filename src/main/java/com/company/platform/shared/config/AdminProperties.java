package com.company.platform.shared.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.admin.*}: {@code statsZone} = múi giờ gom thống kê theo ngày (dữ liệu vẫn lưu UTC).
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(ZoneId statsZone) {

  public AdminProperties {
    statsZone = statsZone == null ? ZoneId.of("Asia/Ho_Chi_Minh") : statsZone;
  }
}
