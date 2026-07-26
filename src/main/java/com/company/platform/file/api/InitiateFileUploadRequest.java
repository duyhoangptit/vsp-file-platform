package com.company.platform.file.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record InitiateFileUploadRequest(
    @Schema(
            description =
                "Mã file_categories quyết định bucket/mime type cho phép/max size — VD IMPORT_RAW"
                    + " (upload CSV để import), EXPORT_RESULT (chỉ server ghi, FE không tự upload).",
            example = "IMPORT_RAW")
        @NotBlank
        String categoryCode,
    @Schema(
            description =
                "Tên file gốc, chỉ để hiển thị UI — không dùng làm object key trên MinIO.",
            example = "users-2026-07.csv")
        @NotBlank
        String originalFilename,
    @Schema(
            description = "MIME type, phải nằm trong allowed_mime_types của category.",
            example = "text/csv")
        @NotBlank
        String mimeType,
    @Schema(
            description = "Kích thước file (bytes), phải <= max_size_bytes của category.",
            example = "1048576")
        @Positive
        long sizeBytes,
    @Schema(
            description = "Loại resource sở hữu file (polymorphic owner_type) — VD USER, JOB.",
            example = "USER")
        @NotBlank
        String ownerType) {}
