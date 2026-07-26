package com.company.platform.file.application;

import java.nio.file.Path;
import java.util.UUID;

public record RegisterGeneratedFileCommand(
    String categoryCode,
    Path localFile,
    String mimeType,
    String originalFilename,
    String checksumSha256,
    String ownerType,
    UUID ownerId,
    UUID uploadedBy,
    UUID jobId) {}
