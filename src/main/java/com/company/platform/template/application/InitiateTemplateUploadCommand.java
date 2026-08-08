package com.company.platform.template.application;

public record InitiateTemplateUploadCommand(
    String category,
    String templateCode,
    String originalFilename,
    String mimeType,
    long sizeBytes) {}
