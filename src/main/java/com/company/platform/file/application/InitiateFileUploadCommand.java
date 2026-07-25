package com.company.platform.file.application;

import java.util.UUID;

public record InitiateFileUploadCommand(
    String categoryCode,
    String originalFilename,
    String mimeType,
    long sizeBytes,
    String ownerType,
    UUID ownerId,
    UUID uploadedBy) {}
