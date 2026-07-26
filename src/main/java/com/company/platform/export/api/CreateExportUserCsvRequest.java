package com.company.platform.export.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * idempotencyKey optional — dùng để chặn double-submit (mục 11 design doc), không có filter nào
 * khác vì business case đầu tiên export toàn bộ app_users.
 */
public record CreateExportUserCsvRequest(
    @Schema(
            description =
                "Key tuỳ chọn để chặn double-submit — gọi lại với cùng key sẽ trả về job đã tạo"
                    + " trước đó thay vì tạo job mới. Bỏ trống nếu không cần idempotency.",
            example = "export-users-2026-07-26")
        String idempotencyKey) {}
