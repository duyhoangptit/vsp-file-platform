package com.company.platform.shared.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.file.application.InitiateMultipartUploadCommand;
import com.company.platform.file.application.MultipartUploadInitiation;
import com.company.platform.file.application.MultipartUploadService;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.storage.multipart.MultipartStorageService;
import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
class CleanupServiceIT {

  private static final long CLEANUP_LOCK_KEY = 725817L;

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void cleanupProperties(DynamicPropertyRegistry registry) {
    registry.add("app.cleanup.job-done-retention", () -> "30d");
    registry.add("app.cleanup.batch-size", () -> "500");
    // Cửa sổ back-date nhỏ để test phiên multipart "quá hạn" mà không phải chờ.
    registry.add("app.cleanup.multipart-upload-timeout", () -> "1s");
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired private CleanupService cleanupService;
  @Autowired private CleanupScheduler cleanupScheduler;
  @Autowired private FileCategoryRepository fileCategoryRepository;
  @Autowired private FileAssetRepository fileAssetRepository;
  @Autowired private JobRepository jobRepository;
  @Autowired private JobStatusService jobStatusService;
  @Autowired private MultipartUploadService multipartUploadService;
  @Autowired private MultipartStorageService multipartStorageService;
  @Autowired private DataSource dataSource;

  private Connection heldLockConnection;

  @AfterEach
  void releaseHeldLock() throws Exception {
    if (heldLockConnection != null && !heldLockConnection.isClosed()) {
      heldLockConnection.close();
    }
  }

  @Test
  void should_softDeleteOnlyExpiredCleanFileAssets_when_categoryHasRetentionDays()
      throws Exception {
    FileCategory category = seedCategory(uniqueCategoryCode("CLEAN"), 7);
    UUID expiredId = seedCleanFileAsset(category, Instant.now().minus(Duration.ofDays(1)));
    UUID notExpiredId = seedCleanFileAsset(category, Instant.now().plus(Duration.ofDays(1)));

    cleanupService.runCleanup();

    assertThat(fileAssetRepository.findById(expiredId).orElseThrow().getDeletedAt()).isNotNull();
    assertThat(fileAssetRepository.findById(notExpiredId).orElseThrow().getDeletedAt()).isNull();
  }

  @Test
  void should_setDeletedAt_when_cleanupSchedulerRunManually() throws Exception {
    // Gọi thẳng CleanupScheduler (bean thật, không phải CleanupService) để chứng minh dây nối
    // scheduler -> service -> repository hoạt động đúng, không chỉ riêng CleanupService.
    FileCategory category = seedCategory(uniqueCategoryCode("SCHED"), 7);
    UUID expiredId = seedCleanFileAsset(category, Instant.now().minus(Duration.ofDays(1)));
    UUID notExpiredId = seedCleanFileAsset(category, Instant.now().plus(Duration.ofDays(1)));

    cleanupScheduler.runCleanupSafely();

    assertThat(fileAssetRepository.findById(expiredId).orElseThrow().getDeletedAt()).isNotNull();
    assertThat(fileAssetRepository.findById(notExpiredId).orElseThrow().getDeletedAt()).isNull();
  }

  @Test
  void should_expireOnlyDoneJobsOlderThanRetention_when_runCleanupCalled() throws Exception {
    UUID oldDoneJobId = seedDoneJob(Instant.now().minus(Duration.ofDays(31)));
    UUID recentDoneJobId = seedDoneJob(Instant.now());

    cleanupService.runCleanup();

    assertThat(jobRepository.findById(oldDoneJobId).orElseThrow().getStatus())
        .isEqualTo(JobStatus.EXPIRED);
    assertThat(jobRepository.findById(recentDoneJobId).orElseThrow().getStatus())
        .isEqualTo(JobStatus.DONE);
  }

  @Test
  void should_skipCleanup_when_anotherSessionHoldsAdvisoryLock() throws Exception {
    FileCategory category = seedCategory(uniqueCategoryCode("LOCK"), 7);
    UUID expiredId = seedCleanFileAsset(category, Instant.now().minus(Duration.ofDays(1)));

    // Cố tình KHÔNG dùng try-with-resources cho heldLockConnection — phải giữ session này mở
    // xuyên suốt vì pg_advisory_lock (session-scoped, không phải xact-scoped) chỉ nhả khi
    // session đóng hoặc gọi pg_advisory_unlock tường minh. @AfterEach lo việc đóng lại.
    heldLockConnection = dataSource.getConnection();
    try (var statement = heldLockConnection.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(" + CLEANUP_LOCK_KEY + ")");
    }

    cleanupService.runCleanup();
    assertThat(fileAssetRepository.findById(expiredId).orElseThrow().getDeletedAt()).isNull();

    heldLockConnection.close();
    heldLockConnection = null;

    cleanupService.runCleanup();
    assertThat(fileAssetRepository.findById(expiredId).orElseThrow().getDeletedAt()).isNotNull();
  }

  @Test
  void should_abortAndSoftDelete_when_multipartUploadStale() throws Exception {
    String code = uniqueCategoryCode("MP");
    String bucketName = "mp-bucket-" + UUID.randomUUID().toString().substring(0, 8);
    fileCategoryRepository.save(
        new FileCategory(
            code,
            bucketName,
            List.of("text/csv"),
            50L * 1024 * 1024,
            false,
            false,
            null,
            5L * 1024 * 1024));
    UUID user = UUID.randomUUID();
    MultipartUploadInitiation init =
        multipartUploadService.initiateMultipart(
            new InitiateMultipartUploadCommand(code, "big.csv", "text/csv", "USER", user, user));
    FileAsset before = fileAssetRepository.findById(init.fileAssetId()).orElseThrow();
    String bucket = before.getBucketName();
    String objectKey = before.getObjectKey();
    String uploadId = before.getMultipartUploadId();
    assertThat(uploadId).isNotBlank();

    // Back-date created_at rõ ràng quá multipart-upload-timeout (1s) — biên rộng để không phụ
    // thuộc timezone của session Postgres so với JVM (cùng cách seedDoneJob lùi 31 ngày).
    execute(
        "UPDATE file_assets SET created_at = now() - interval '2 days' WHERE id = ?",
        init.fileAssetId());

    cleanupService.runCleanup();

    FileAsset after = fileAssetRepository.findById(init.fileAssetId()).orElseThrow();
    assertThat(after.getDeletedAt()).isNotNull();
    assertThat(after.getMultipartUploadId()).isNull();
    assertThat(multipartStorageService.listParts(bucket, objectKey, uploadId)).isEmpty();
  }

  // code là VARCHAR(50) — cắt ngắn UUID để không vượt giới hạn cột.
  private String uniqueCategoryCode(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private FileCategory seedCategory(String code, int retentionDays) {
    FileCategory category =
        new FileCategory(
            code, "bucket-" + code, List.of("text/csv"), 1024, false, false, retentionDays, null);
    return fileCategoryRepository.save(category);
  }

  private UUID seedCleanFileAsset(FileCategory category, Instant expiresAt) throws Exception {
    UUID id = UUID.randomUUID();
    FileAsset fileAsset =
        new FileAsset(
            id,
            category.getCode(),
            category.getBucketName(),
            UUID.randomUUID().toString(),
            "file.csv",
            "text/csv",
            10,
            "USER",
            UUID.randomUUID(),
            UUID.randomUUID(),
            expiresAt);
    fileAssetRepository.save(fileAsset);
    // FileAsset luôn khởi tạo storage_status = UPLOADING (mục 5 design doc) — set thẳng CLEAN
    // qua SQL cho gọn, bỏ qua luồng CAS UPLOADING->UPLOADED_DMZ->CLEAN thật (cần MinIO thật để
    // verify object), vì test này chỉ tập trung vào CleanupService, không phải upload flow.
    execute("UPDATE file_assets SET storage_status = 'CLEAN' WHERE id = ?", id);
    return id;
  }

  private UUID seedDoneJob(Instant createdAt) throws Exception {
    UUID jobId = UUID.randomUUID();
    jobRepository.save(
        new Job(
            jobId, JobType.EXPORT_USER_CSV, Map.of("k", "v"), null, null, null, UUID.randomUUID()));
    jobStatusService.tryMarkProcessing(jobId);
    jobStatusService.markDone(jobId, "bucket/result.csv", 1);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE jobs SET created_at = ? WHERE id = ?")) {
      statement.setTimestamp(1, Timestamp.from(createdAt));
      statement.setObject(2, jobId);
      statement.executeUpdate();
    }
    return jobId;
  }

  private void execute(String sql, UUID id) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }
}
