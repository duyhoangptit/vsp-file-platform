package com.company.platform.file.api;

import com.company.platform.file.application.FileDownloadResult;
import java.time.Instant;

public record FileDownloadResponse(String downloadUrl, Instant expiresAt) {

  public static FileDownloadResponse from(FileDownloadResult result) {
    return new FileDownloadResponse(result.downloadUrl(), result.expiresAt());
  }
}
