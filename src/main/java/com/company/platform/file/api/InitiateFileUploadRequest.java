package com.company.platform.file.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record InitiateFileUploadRequest(
    @NotBlank String categoryCode,
    @NotBlank String originalFilename,
    @NotBlank String mimeType,
    @Positive long sizeBytes,
    @NotBlank String ownerType) {}
