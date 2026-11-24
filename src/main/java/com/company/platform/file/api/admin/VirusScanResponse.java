package com.company.platform.file.api.admin;

import com.company.platform.file.application.admin.VirusScanOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VirusScanResponse(
    @Schema(description = "Công tắc tổng virus scan") boolean globalEnabled,
    @Schema(description = "Gửi lại khi PUT (optimistic lock)") long version,
    UUID updatedBy,
    Instant updatedAt,
    @Schema(
            description =
                "false = chưa có engine scan thật; bật cờ chưa làm thay đổi luồng upload/download")
        boolean engineIntegrated,
    List<CategoryState> categories) {

  public record CategoryState(
      String code,
      boolean requireVirusScan,
      @Schema(description = "globalEnabled && requireVirusScan") boolean effective) {}

  public static VirusScanResponse from(VirusScanOverview o) {
    return new VirusScanResponse(
        o.globalEnabled(),
        o.version(),
        o.updatedBy(),
        o.updatedAt(),
        o.engineIntegrated(),
        o.categories().stream()
            .map(c -> new CategoryState(c.code(), c.requireVirusScan(), c.effective()))
            .toList());
  }
}
