package com.company.platform.job.scheduler;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.job.fallback-poller")
public record JobFallbackPollerProperties(Duration interval) {}
