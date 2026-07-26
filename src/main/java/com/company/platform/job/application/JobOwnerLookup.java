package com.company.platform.job.application;

import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.shared.auth.ResourceOwnerLookup;
import com.company.platform.shared.auth.ResourceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JobOwnerLookup implements ResourceOwnerLookup {

  private final JobRepository jobRepository;

  public JobOwnerLookup(JobRepository jobRepository) {
    this.jobRepository = jobRepository;
  }

  @Override
  public ResourceType supports() {
    return ResourceType.JOB;
  }

  @Override
  public Optional<UUID> findOwnerId(String resourceId) {
    UUID id;
    try {
      id = UUID.fromString(resourceId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return jobRepository.findById(id).map(Job::getCreatedBy);
  }
}
