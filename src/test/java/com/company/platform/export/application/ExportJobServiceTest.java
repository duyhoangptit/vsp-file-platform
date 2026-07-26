package com.company.platform.export.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.file.application.FileDownloadResult;
import com.company.platform.file.application.FileDownloadService;
import com.company.platform.job.application.JobSubmissionService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobNotFoundException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.UserContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ExportJobServiceTest {

  private final JobSubmissionService jobSubmissionService = mock(JobSubmissionService.class);
  private final JobRepository jobRepository = mock(JobRepository.class);
  private final FileDownloadService fileDownloadService = mock(FileDownloadService.class);
  private final ExportJobService exportJobService =
      new ExportJobService(
          jobSubmissionService, jobRepository, new ObjectMapper(), fileDownloadService);

  @Test
  void should_throwAccessDeniedException_when_userIsNotAdmin() {
    UserContext member = new UserContext(UUID.randomUUID(), Set.of("MEMBER"));

    assertThatThrownBy(() -> exportJobService.submitUserCsvExport(member, null))
        .isInstanceOf(AccessDeniedException.class);

    verify(jobSubmissionService, never()).submit(any(), any(), any(), any());
  }

  @Test
  void should_submitExportUserCsvJob_when_userIsAdmin() {
    UUID userId = UUID.randomUUID();
    UserContext admin = new UserContext(userId, Set.of("ADMIN"));
    Job job =
        new Job(UUID.randomUUID(), JobType.EXPORT_USER_CSV, Map.of(), null, null, null, userId);
    when(jobSubmissionService.submit(eq(JobType.EXPORT_USER_CSV), any(), eq("idem-1"), eq(userId)))
        .thenReturn(job);

    Job result = exportJobService.submitUserCsvExport(admin, "idem-1");

    assertThat(result).isEqualTo(job);
  }

  @Test
  void should_passEmptyObjectPayload_when_submittingExport() {
    UUID userId = UUID.randomUUID();
    UserContext admin = new UserContext(userId, Set.of("ADMIN"));
    when(jobSubmissionService.submit(any(), any(), any(), any()))
        .thenReturn(
            new Job(
                UUID.randomUUID(), JobType.EXPORT_USER_CSV, Map.of(), null, null, null, userId));

    exportJobService.submitUserCsvExport(admin, null);

    ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
    verify(jobSubmissionService)
        .submit(eq(JobType.EXPORT_USER_CSV), payloadCaptor.capture(), isNull(), eq(userId));
    assertThat(payloadCaptor.getValue()).isInstanceOf(ObjectNode.class);
    assertThat(payloadCaptor.getValue().isEmpty()).isTrue();
  }

  @Test
  void should_returnJobWithNullDownloadUrl_when_jobExistsAndIsNotDone() {
    UUID jobId = UUID.randomUUID();
    Job job =
        new Job(jobId, JobType.EXPORT_USER_CSV, Map.of(), null, null, null, UUID.randomUUID());
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    ExportJobDetail result = exportJobService.getJob(jobId);

    assertThat(result.job()).isEqualTo(job);
    assertThat(result.downloadUrl()).isNull();
    verify(fileDownloadService, never()).findDownloadUrlByJobId(any());
  }

  @Test
  void should_returnDownloadUrl_when_jobIsDoneAndFileIsClean() {
    UUID jobId = UUID.randomUUID();
    Job doneJob = mock(Job.class);
    when(doneJob.getStatus()).thenReturn(JobStatus.DONE);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(doneJob));
    when(fileDownloadService.findDownloadUrlByJobId(jobId))
        .thenReturn(Optional.of(new FileDownloadResult("https://minio/presigned", Instant.now())));

    ExportJobDetail result = exportJobService.getJob(jobId);

    assertThat(result.downloadUrl()).isEqualTo("https://minio/presigned");
  }

  @Test
  void should_throwJobNotFoundException_when_jobDoesNotExist() {
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> exportJobService.getJob(jobId))
        .isInstanceOf(JobNotFoundException.class);
  }
}
