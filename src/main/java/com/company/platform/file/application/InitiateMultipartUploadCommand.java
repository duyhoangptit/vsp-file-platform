package com.company.platform.file.application;

import java.util.UUID;

public record InitiateMultipartUploadCommand(
    String categoryCode,
    String originalFilename,
    String mimeType,
    String ownerType,
    UUID ownerId,
    UUID uploadedBy) {}
