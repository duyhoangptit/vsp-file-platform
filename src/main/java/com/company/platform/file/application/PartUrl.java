package com.company.platform.file.application;

import java.time.Instant;

public record PartUrl(int partNumber, String url, Instant expiresAt) {}
