package com.company.platform.file.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ConfirmFileUploadRequest(
    @Schema(
            description =
                "SHA-256 checksum (hex) tính bởi client sau khi upload xong — server verify"
                    + " tồn tại + kích thước qua HEAD object trên MinIO trước khi lưu checksum này.",
            example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        @NotBlank
        String checksumSha256) {}
