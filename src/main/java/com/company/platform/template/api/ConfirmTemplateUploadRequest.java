package com.company.platform.template.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConfirmTemplateUploadRequest(
    @Schema(description = "fileAssetId nhận được từ bước initiate.") @NotNull UUID fileAssetId,
    @Schema(description = "Cụm nghiệp vụ lớn, phải khớp bước initiate.", example = "MEMBER")
        @NotBlank
        String category,
    @Schema(description = "Mã function, phải khớp bước initiate.", example = "MEMBER_IMPORT")
        @NotBlank
        String templateCode,
    @Schema(description = "SHA-256 checksum client tự tính sau khi upload xong.") @NotBlank
        String checksumSha256,
    @Schema(description = "Mô tả version này (optional).", example = "Thêm cột ngày sinh")
        String description) {}
