package com.company.platform.file.api;

import com.company.platform.file.application.FileUploadInitiation;
import java.time.Instant;
import java.util.UUID;

public record InitiateFileUploadResponse(UUID fileAssetId, String uploadUrl, Instant expiresAt) {

  public static InitiateFileUploadResponse from(FileUploadInitiation initiation) {
    return new InitiateFileUploadResponse(
        initiation.fileAssetId(), initiation.uploadUrl(), initiation.expiresAt());
  }
}
