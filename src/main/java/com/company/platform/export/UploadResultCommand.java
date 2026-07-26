package com.company.platform.export;

import java.nio.file.Path;
import java.util.UUID;

public record UploadResultCommand(
    Path localFile,
    String categoryCode,
    String mimeType,
    String originalFilename,
    String checksumSha256,
    String ownerType,
    UUID ownerId,
    UUID uploadedBy,
    UUID jobId) {}
