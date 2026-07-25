package com.company.platform.file.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.minio")
public record MinioProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    long connectTimeoutMillis,
    long writeTimeoutMillis,
    long readTimeoutMillis) {}
