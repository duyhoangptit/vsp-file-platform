package com.company.platform.file.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record InitiateMultipartUploadRequest(
    @Schema(
            description =
                "Mã file_categories quyết định bucket/mime type cho phép/max size + kích thước part"
                    + " (multipart_part_size_bytes phải khác null) — VD IMPORT_RAW.",
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
            description = "Loại resource sở hữu file (polymorphic owner_type) — VD USER, JOB.",
            example = "USER")
        @NotBlank
        String ownerType) {}
