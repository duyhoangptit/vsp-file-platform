package com.company.platform.file.api;

import jakarta.validation.constraints.NotBlank;

public record ConfirmFileUploadRequest(@NotBlank String checksumSha256) {}
