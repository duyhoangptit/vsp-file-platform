package com.company.platform.shared.cleanup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cleanup")
public record CleanupProperties(
    Duration interval, int batchSize, Duration jobDoneRetention, Duration multipartUploadTimeout) {}
