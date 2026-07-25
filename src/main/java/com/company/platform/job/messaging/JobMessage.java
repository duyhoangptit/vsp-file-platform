package com.company.platform.job.messaging;

import com.company.platform.job.domain.JobType;
import java.util.UUID;

public record JobMessage(UUID jobId, JobType jobType) {}
