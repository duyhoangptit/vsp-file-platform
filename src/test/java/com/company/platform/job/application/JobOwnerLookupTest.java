package com.company.platform.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.auth.ResourceType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobOwnerLookupTest {

  private final JobRepository jobRepository = mock(JobRepository.class);
  private final JobOwnerLookup jobOwnerLookup = new JobOwnerLookup(jobRepository);

  @Test
  void should_supportJobResourceType() {
    assertThat(jobOwnerLookup.supports()).isEqualTo(ResourceType.JOB);
  }

  @Test
  void should_returnCreatedBy_when_jobExists() {
    UUID jobId = UUID.randomUUID();
    UUID createdBy = UUID.randomUUID();
    Job job = new Job(jobId, JobType.EXPORT_USER_CSV, Map.of(), null, null, null, createdBy);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    assertThat(jobOwnerLookup.findOwnerId(jobId.toString())).contains(createdBy);
  }

  @Test
  void should_returnEmpty_when_jobDoesNotExist() {
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

    assertThat(jobOwnerLookup.findOwnerId(jobId.toString())).isEmpty();
  }

  @Test
  void should_returnEmpty_when_resourceIdIsNotAValidUuid() {
    assertThat(jobOwnerLookup.findOwnerId("not-a-uuid")).isEmpty();
  }
}
