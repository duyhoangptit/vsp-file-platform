package com.company.platform.template.application;

import java.time.Instant;
import java.util.UUID;

public record TemplateUploadInitiation(UUID fileAssetId, String uploadUrl, Instant expiresAt) {}
