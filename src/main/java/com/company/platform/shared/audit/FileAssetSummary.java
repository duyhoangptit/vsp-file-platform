package com.company.platform.shared.audit;

import java.util.UUID;

public record FileAssetSummary(
    UUID id, String originalFilename, String mimeType, long sizeBytes, String categoryCode) {}
