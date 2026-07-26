package com.company.platform.import_.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.export.ResultUploader;
import com.company.platform.export.UploadResultCommand;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.import_.FileParser;
import com.company.platform.import_.ImportKitFactory;
import com.company.platform.import_.RecordPersister;
import com.company.platform.import_.SchemaValidationException;
import com.company.platform.import_.SchemaValidator;
import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.processor.JobContext;
import com.company.platform.job.processor.JobContext.ProgressReporter;
import com.company.platform.job.processor.JobResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

class ImportUserProcessorTest {

  private final JobStatusService jobStatusService = mock(JobStatusService.class);
  private final TaskScheduler heartbeatScheduler = mock(TaskScheduler.class);
  private final FileAssetRepository fileAssetRepository = mock(FileAssetRepository.class);
  private final MinioStorageService minioStorageService = mock(MinioStorageService.class);

  @SuppressWarnings("unchecked")
  private final ImportKitFactory<UserImportRecord> kitFactory = mock(ImportKitFactory.class);

  @SuppressWarnings("unchecked")
  private final FileParser<UserImportRecord> fileParser = mock(FileParser.class);

  @SuppressWarnings("unchecked")
  private final SchemaValidator<UserImportRecord> schemaValidator = mock(SchemaValidator.class);

  @SuppressWarnings("unchecked")
  private final RecordPersister<UserImportRecord> recordPersister = mock(RecordPersister.class);

  private final JobRepository jobRepository = mock(JobRepository.class);
  private final ResultUploader resultUploader = mock(ResultUploader.class);

  private final ImportUserProcessor processor =
      new ImportUserProcessor(
          jobStatusService,
          heartbeatScheduler,
          Runnable::run,
          fileAssetRepository,
          minioStorageService,
          kitFactory,
          jobRepository,
          resultUploader,
          2);

  @Test
  void should_returnImportUser_when_askedWhatItSupports() {
    assertThat(processor.supports()).isEqualTo(JobType.IMPORT_USER);
  }

  @Test
  void should_throw_when_payloadMissingFileAssetId() {
    assertThatThrownBy(() -> processor.validate(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_throw_when_fileAssetIdIsNotAValidUuid() {
    assertThatThrownBy(() -> processor.validate(Map.of("fileAssetId", "not-a-uuid")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_notThrow_when_fileAssetIdIsAValidUuid() {
    processor.validate(Map.of("fileAssetId", UUID.randomUUID().toString()));
  }

  @Test
  void should_throwFileAssetNotFoundException_when_fileAssetDoesNotExist() {
    UUID jobId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    when(fileAssetRepository.findById(fileAssetId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> processor.doExecute(context(jobId, fileAssetId)))
        .isInstanceOf(FileAssetNotFoundException.class);
  }

  @Test
  void should_throwFileNotAvailableForDownloadException_when_storageStatusIsNotClean() {
    UUID jobId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    FileAsset fileAsset = mock(FileAsset.class);
    when(fileAsset.getStorageStatus()).thenReturn(StorageStatus.UPLOADED_DMZ);
    when(fileAssetRepository.findById(fileAssetId)).thenReturn(Optional.of(fileAsset));

    assertThatThrownBy(() -> processor.doExecute(context(jobId, fileAssetId)))
        .isInstanceOf(FileNotAvailableForDownloadException.class);
  }

  @Test
  void should_throwSchemaValidationException_when_headerMissingRequiredColumn() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    stubCleanFileAsset(fileAssetId, "import-raw", "object-key-1");
    when(fileParser.header()).thenReturn(List.of("full_name", "role"));
    doThrow(new SchemaValidationException("Thiếu cột bắt buộc: email"))
        .when(schemaValidator)
        .validateHeader(List.of("full_name", "role"));

    assertThatThrownBy(() -> processor.doExecute(context(jobId, fileAssetId)))
        .isInstanceOf(SchemaValidationException.class)
        .hasMessage("Thiếu cột bắt buộc: email");

    verify(recordPersister, never()).persistBatch(any());
    verify(resultUploader, never()).upload(any());
  }

  @Test
  void should_persistInBatchesAndReportProgress_when_executingHappyPath() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    stubCleanFileAsset(fileAssetId, "import-raw", "object-key-1");
    when(fileParser.header()).thenReturn(List.of("email", "full_name", "status", "role"));

    UserImportRecord r1 = record("a@b.com");
    UserImportRecord r2 = record("c@d.com");
    UserImportRecord r3 = record("e@f.com");
    when(fileParser.readNextRecord()).thenReturn(r1, r2, r3, null);

    ProgressReporter progressReporter = mock(ProgressReporter.class);

    JobResult result =
        processor.doExecute(
            new JobContext(
                jobId,
                JobType.IMPORT_USER,
                Map.of("fileAssetId", fileAssetId.toString()),
                progressReporter));

    assertThat(result.resultLocation()).isNull();
    assertThat(result.processedUnits()).isEqualTo(3);

    verify(schemaValidator).validateHeader(List.of("email", "full_name", "status", "role"));
    verify(schemaValidator).validate(r1, 1);
    verify(schemaValidator).validate(r2, 2);
    verify(schemaValidator).validate(r3, 3);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<UserImportRecord>> batchCaptor = ArgumentCaptor.forClass(List.class);
    verify(recordPersister, times(2)).persistBatch(batchCaptor.capture());
    List<List<UserImportRecord>> batches = batchCaptor.getAllValues();
    assertThat(batches.get(0)).containsExactly(r1, r2);
    assertThat(batches.get(1)).containsExactly(r3);

    verify(progressReporter).report(2);
    verify(progressReporter).report(3);
    verify(resultUploader, never()).upload(any());
  }

  @Test
  void should_notPersist_when_noRecordsInFile() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    stubCleanFileAsset(fileAssetId, "import-raw", "object-key-1");
    when(fileParser.header()).thenReturn(List.of("email", "full_name", "status", "role"));
    when(fileParser.readNextRecord()).thenReturn(null);

    JobResult result = processor.doExecute(context(jobId, fileAssetId));

    assertThat(result.processedUnits()).isEqualTo(0);
    verify(recordPersister, never()).persistBatch(any());
    verify(resultUploader, never()).upload(any());
  }

  @Test
  void should_skipInvalidRowsAndUploadErrorReport_when_someRowsFailValidation() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    UUID createdBy = UUID.randomUUID();
    stubCleanFileAsset(fileAssetId, "import-raw", "object-key-1");
    when(fileParser.header()).thenReturn(List.of("email", "full_name", "status", "role"));
    when(jobRepository.findById(jobId))
        .thenReturn(
            Optional.of(
                new Job(jobId, JobType.IMPORT_USER, Map.of(), null, null, null, createdBy)));

    UserImportRecord valid1 = record("a@b.com");
    UserImportRecord invalid = record("");
    UserImportRecord valid2 = record("c@d.com");
    when(fileParser.readNextRecord()).thenReturn(valid1, invalid, valid2, null);
    doThrow(new SchemaValidationException(2, "cột email không được để trống"))
        .when(schemaValidator)
        .validate(invalid, 2);

    FileAsset reportAsset = mock(FileAsset.class);
    when(reportAsset.getObjectKey()).thenReturn("error-report-key");
    when(resultUploader.upload(any(UploadResultCommand.class))).thenReturn(reportAsset);

    JobResult result = processor.doExecute(context(jobId, fileAssetId));

    assertThat(result.resultLocation()).isEqualTo("error-report-key");
    assertThat(result.processedUnits()).isEqualTo(2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<UserImportRecord>> batchCaptor = ArgumentCaptor.forClass(List.class);
    verify(recordPersister, times(1)).persistBatch(batchCaptor.capture());
    assertThat(batchCaptor.getValue()).containsExactly(valid1, valid2);

    ArgumentCaptor<UploadResultCommand> commandCaptor =
        ArgumentCaptor.forClass(UploadResultCommand.class);
    verify(resultUploader).upload(commandCaptor.capture());
    UploadResultCommand command = commandCaptor.getValue();
    assertThat(command.categoryCode()).isEqualTo("IMPORT_ERROR_REPORT");
    assertThat(command.mimeType()).isEqualTo("text/csv");
    assertThat(command.ownerId()).isEqualTo(createdBy);
    assertThat(command.uploadedBy()).isEqualTo(createdBy);
    assertThat(command.jobId()).isEqualTo(jobId);
    assertThat(command.checksumSha256()).hasSize(64);
  }

  private void stubCleanFileAsset(UUID fileAssetId, String bucketName, String objectKey)
      throws Exception {
    FileAsset fileAsset = mock(FileAsset.class);
    when(fileAsset.getStorageStatus()).thenReturn(StorageStatus.CLEAN);
    when(fileAsset.getBucketName()).thenReturn(bucketName);
    when(fileAsset.getObjectKey()).thenReturn(objectKey);
    when(fileAssetRepository.findById(fileAssetId)).thenReturn(Optional.of(fileAsset));

    InputStream objectStream = new ByteArrayInputStream(new byte[0]);
    when(minioStorageService.getObject(bucketName, objectKey)).thenReturn(objectStream);
    when(kitFactory.createFileParser(objectStream)).thenReturn(fileParser);
    when(kitFactory.createSchemaValidator()).thenReturn(schemaValidator);
    when(kitFactory.createRecordPersister()).thenReturn(recordPersister);
  }

  private JobContext context(UUID jobId, UUID fileAssetId) {
    return new JobContext(
        jobId, JobType.IMPORT_USER, Map.of("fileAssetId", fileAssetId.toString()));
  }

  private UserImportRecord record(String email) {
    return new UserImportRecord(email, "Name", null, "ACTIVE", "MEMBER", null, null);
  }
}
