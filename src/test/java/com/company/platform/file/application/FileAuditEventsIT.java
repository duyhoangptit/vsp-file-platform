package com.company.platform.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.audit.AuditLog;
import com.company.platform.shared.audit.AuditLogRepository;
import com.company.platform.shared.audit.FileAuditActions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class FileAuditEventsIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void minioProperties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired private FileUploadService fileUploadService;
  @Autowired private FileDownloadService fileDownloadService;
  @Autowired private FileCategoryRepository fileCategoryRepository;
  @Autowired private JobRepository jobRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  private String categoryCode;

  @BeforeEach
  void seedCategory() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    categoryCode = "AUDIT_CAT_" + suffix;
    fileCategoryRepository.save(
        new FileCategory(
            categoryCode,
            "audit-test-" + suffix,
            List.of("text/csv"),
            1_000_000L,
            false,
            false,
            null,
            null));
  }

  private List<AuditLog> logs(UUID fileAssetId, String action) {
    return auditLogRepository.findByResourceId(fileAssetId).stream()
        .filter(l -> l.getAction().equals(action))
        .toList();
  }

  private UUID uploadAndConfirm(UUID actor) throws Exception {
    byte[] bytes = ("id\n" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    FileUploadInitiation initiation =
        fileUploadService.initiateUpload(
            new InitiateFileUploadCommand(
                categoryCode, "a.csv", "text/csv", bytes.length, "USER", actor, actor));
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(initiation.uploadUrl()))
                .PUT(BodyPublishers.ofByteArray(bytes))
                .build(),
            BodyHandlers.discarding());
    fileUploadService.confirmUpload(initiation.fileAssetId(), "sha", actor);
    return initiation.fileAssetId();
  }

  @Test
  void should_recordUploadCompletedOnce_when_confirmCalledTwice() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID fileAssetId = uploadAndConfirm(actor);
    fileUploadService.confirmUpload(fileAssetId, "sha", actor);

    List<AuditLog> completed = logs(fileAssetId, FileAuditActions.UPLOAD_COMPLETED);
    assertThat(completed).hasSize(1);
    AuditLog log = completed.get(0);
    assertThat(log.getActorId()).isEqualTo(actor);
    assertThat(log.getResourceType()).isEqualTo("FILE_ASSET");
    assertThat(log.getMetadata())
        .containsEntry("mimeType", "text/csv")
        .containsEntry("categoryCode", categoryCode)
        .containsEntry("source", "USER_UPLOAD")
        .containsKey("sizeBytes");
  }

  @Test
  void should_recordUploadCompletedWithNullActor_when_serverGeneratedFile() throws Exception {
    Path tempFile = Files.createTempFile("audit-it-", ".csv");
    Files.writeString(tempFile, "id\n1\n");
    UUID ownerId = UUID.randomUUID();
    Job job =
        jobRepository.save(
            new Job(
                UUID.randomUUID(), JobType.EXPORT_USER_CSV, Map.of(), null, null, null, ownerId));
    try {
      FileAsset asset =
          fileUploadService.registerServerGeneratedFile(
              new RegisterGeneratedFileCommand(
                  categoryCode,
                  tempFile,
                  "text/csv",
                  "e.csv",
                  "sha",
                  "USER",
                  ownerId,
                  ownerId,
                  job.getId()));
      List<AuditLog> completed = logs(asset.getId(), FileAuditActions.UPLOAD_COMPLETED);
      assertThat(completed).hasSize(1);
      assertThat(completed.get(0).getActorId()).isNull();
      assertThat(completed.get(0).getMetadata()).containsEntry("source", "JOB_GENERATED");
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void should_recordDownloadUrlIssued_when_fileIsClean() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID fileAssetId = uploadAndConfirm(actor);

    fileDownloadService.generateDownloadUrl(fileAssetId, actor, "FILE_API");

    List<AuditLog> issued = logs(fileAssetId, FileAuditActions.DOWNLOAD_URL_ISSUED);
    assertThat(issued).hasSize(1);
    assertThat(issued.get(0).getActorId()).isEqualTo(actor);
    assertThat(issued.get(0).getMetadata())
        .containsEntry("via", "FILE_API")
        .containsKey("expiresInSeconds");
  }

  @Test
  void should_notRecordDownloadUrlIssued_when_fileNotClean() {
    UUID actor = UUID.randomUUID();
    FileUploadInitiation initiation =
        fileUploadService.initiateUpload(
            new InitiateFileUploadCommand(
                categoryCode, "a.csv", "text/csv", 10, "USER", actor, actor));

    assertThatThrownBy(
            () ->
                fileDownloadService.generateDownloadUrl(
                    initiation.fileAssetId(), actor, "FILE_API"))
        .isInstanceOf(FileNotAvailableForDownloadException.class);
    assertThat(logs(initiation.fileAssetId(), FileAuditActions.DOWNLOAD_URL_ISSUED)).isEmpty();
  }

  @Test
  void should_notRecordDownloadUrlIssued_when_urlEmbeddedInExportStatus() throws Exception {
    Path tempFile = Files.createTempFile("audit-it-", ".csv");
    Files.writeString(tempFile, "id\n1\n");
    UUID ownerId = UUID.randomUUID();
    Job job =
        jobRepository.save(
            new Job(
                UUID.randomUUID(), JobType.EXPORT_USER_CSV, Map.of(), null, null, null, ownerId));
    try {
      FileAsset asset =
          fileUploadService.registerServerGeneratedFile(
              new RegisterGeneratedFileCommand(
                  categoryCode,
                  tempFile,
                  "text/csv",
                  "e.csv",
                  "sha",
                  "USER",
                  ownerId,
                  ownerId,
                  job.getId()));

      assertThat(fileDownloadService.findDownloadUrlByJobId(job.getId())).isPresent();
      assertThat(logs(asset.getId(), FileAuditActions.DOWNLOAD_URL_ISSUED)).isEmpty();
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }
}
