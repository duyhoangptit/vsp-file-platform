package com.company.platform.job.processor;

import com.company.platform.job.domain.JobType;
import java.util.Map;
import java.util.UUID;

public record JobContext(UUID jobId, JobType jobType, Map<String, Object> payload) {}
