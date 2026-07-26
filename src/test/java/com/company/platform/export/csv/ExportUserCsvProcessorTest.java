package com.company.platform.export.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.export.DataReader;
import com.company.platform.export.ExportKitFactory;
import com.company.platform.export.RecordWriter;
import com.company.platform.export.ResultUploader;
import com.company.platform.export.UploadResultCommand;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobNotFoundException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.processor.JobContext;
import com.company.platform.job.processor.JobResult;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.scheduling.TaskScheduler;

class ExportUserCsvProcessorTest {

  private final JobStatusService jobStatusService = mock(JobStatusService.class);
  private final TaskScheduler heartbeatScheduler = mock(TaskScheduler.class);
  private final JobRepository jobRepository = mock(JobRepository.class);

  @SuppressWarnings("unchecked")
  private final ExportKitFactory<AppUser> kitFactory = mock(ExportKitFactory.class);

  @SuppressWarnings("unchecked")
  private final DataReader<AppUser> dataReader = mock(DataReader.class);

  private final ResultUploader resultUploader = mock(ResultUploader.class);

  private final ExportUserCsvProcessor processor =
      new ExportUserCsvProcessor(
          jobStatusService, heartbeatScheduler, Runnable::run, jobRepository, kitFactory, 2);

  @Test
  void should_notThrow_when_validatingArbitraryPayload() {
    processor.validate(Map.of("anything", "goes"));
  }

  @Test
  void should_throwJobNotFoundException_when_jobDoesNotExist() {
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> processor.doExecute(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of())))
        .isInstanceOf(JobNotFoundException.class);
  }

  @Test
  void should_pageThroughAllRecords_and_uploadResult_when_executing() throws IOException {
    UUID jobId = UUID.randomUUID();
    UUID createdBy = UUID.randomUUID();
    Job job = new Job(jobId, JobType.EXPORT_USER_CSV, Map.of(), null, null, null, createdBy);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    AppUser user1 = appUser();
    AppUser user2 = appUser();
    AppUser user3 = appUser();
    when(dataReader.readNextPage(isNull(), eq(2))).thenReturn(page(user1, user2));
    when(dataReader.readNextPage(eq(user2.getId().toString()), eq(2))).thenReturn(page(user3));
    when(dataReader.readNextPage(eq(user3.getId().toString()), eq(2))).thenReturn(page());
    when(kitFactory.createDataReader()).thenReturn(dataReader);
    when(kitFactory.createRecordWriter(any(OutputStream.class))).thenAnswer(inv -> fakeWriter());
    when(kitFactory.createResultUploader()).thenReturn(resultUploader);
    FileAsset uploadedAsset = mock(FileAsset.class);
    when(uploadedAsset.getObjectKey()).thenReturn("result-object-key");
    when(resultUploader.upload(any(UploadResultCommand.class))).thenReturn(uploadedAsset);

    JobResult result =
        processor.doExecute(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    assertThat(result.resultLocation()).isEqualTo("result-object-key");
    assertThat(result.processedUnits()).isEqualTo(3);

    ArgumentCaptor<UploadResultCommand> commandCaptor =
        ArgumentCaptor.forClass(UploadResultCommand.class);
    verify(resultUploader).upload(commandCaptor.capture());
    UploadResultCommand command = commandCaptor.getValue();
    assertThat(command.categoryCode()).isEqualTo("EXPORT_RESULT");
    assertThat(command.mimeType()).isEqualTo("text/csv");
    assertThat(command.ownerType()).isEqualTo("USER");
    assertThat(command.ownerId()).isEqualTo(createdBy);
    assertThat(command.uploadedBy()).isEqualTo(createdBy);
    assertThat(command.jobId()).isEqualTo(jobId);
    assertThat(command.checksumSha256()).hasSize(64);
  }

  private AppUser appUser() {
    return new AppUser(
        UUID.randomUUID(),
        "user@example.com",
        "Demo User",
        null,
        "ACTIVE",
        "MEMBER",
        "VN",
        "Hanoi",
        Instant.now(),
        Instant.now());
  }

  private Page<AppUser> page(AppUser... users) {
    return new PageImpl<>(List.of(users));
  }

  private RecordWriter<AppUser> fakeWriter() {
    return new RecordWriter<>() {
      @Override
      public void writeRecord(AppUser record) {}

      @Override
      public void close() {}
    };
  }
}
