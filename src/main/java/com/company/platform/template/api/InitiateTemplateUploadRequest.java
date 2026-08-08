package com.company.platform.template.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record InitiateTemplateUploadRequest(
    @Schema(description = "Cụm nghiệp vụ lớn.", example = "MEMBER") @NotBlank String category,
    @Schema(description = "Mã function.", example = "MEMBER_IMPORT") @NotBlank String templateCode,
    @Schema(
            description = "Tên file gốc, chỉ để hiển thị UI.",
            example = "member-import-template.xlsx")
        @NotBlank
        String originalFilename,
    @Schema(
            description =
                "MIME type, phải nằm trong allowed_mime_types của category FILE_TEMPLATE.",
            example = "text/csv")
        @NotBlank
        String mimeType,
    @Schema(description = "Kích thước file (bytes).", example = "20480") @Positive
        long sizeBytes) {}
