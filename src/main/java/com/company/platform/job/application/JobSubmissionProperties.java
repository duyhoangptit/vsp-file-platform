package com.company.platform.job.application;

import com.company.platform.job.domain.JobType;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.job")
public record JobSubmissionProperties(
    int maxActivePerUser, Map<JobType, RateLimitConfig> rateLimit) {

  public record RateLimitConfig(long capacity, long refillTokens, Duration refillPeriod) {}
}
