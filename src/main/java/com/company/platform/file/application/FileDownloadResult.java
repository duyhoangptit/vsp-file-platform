package com.company.platform.file.application;

import java.time.Instant;

public record FileDownloadResult(String downloadUrl, Instant expiresAt) {}
