package com.company.platform.file.application.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param engineIntegrated luôn {@code false} ở giai đoạn này: chưa có engine scan thật (ClamAV),
 *     bật cờ chưa làm thay đổi luồng upload.
 */
public record VirusScanOverview(
    boolean globalEnabled,
    long version,
    UUID updatedBy,
    Instant updatedAt,
    boolean engineIntegrated,
    List<CategoryScanState> categories) {}
