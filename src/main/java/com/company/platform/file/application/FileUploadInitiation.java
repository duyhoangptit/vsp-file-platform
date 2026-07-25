package com.company.platform.file.application;

import java.time.Instant;
import java.util.UUID;

public record FileUploadInitiation(UUID fileAssetId, String uploadUrl, Instant expiresAt) {}
