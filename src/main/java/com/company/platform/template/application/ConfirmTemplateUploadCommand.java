package com.company.platform.template.application;

import java.util.UUID;

public record ConfirmTemplateUploadCommand(
    UUID fileAssetId,
    String category,
    String templateCode,
    String checksumSha256,
    String description) {}
