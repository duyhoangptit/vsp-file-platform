package com.company.platform.import_.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateImportUserRequest(
    @Schema(
            description =
                "ID file asset đã upload + confirm (storageStatus = CLEAN) dưới category"
                    + " IMPORT_RAW — lấy từ response của POST /api/files/{fileAssetId}/confirm.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        UUID fileAssetId,
    @Schema(
            description =
                "Key tuỳ chọn để chặn double-submit — gọi lại với cùng key sẽ trả về job đã tạo"
                    + " trước đó thay vì tạo job mới.",
            example = "import-users-2026-07-26")
        String idempotencyKey) {}
