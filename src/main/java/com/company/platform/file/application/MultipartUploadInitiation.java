package com.company.platform.file.application;

import java.util.UUID;

public record MultipartUploadInitiation(UUID fileAssetId, long partSizeBytes, int maxParts) {}
