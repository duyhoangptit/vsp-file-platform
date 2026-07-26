package com.company.platform.import_.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.job.processor.JobContext;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verify 2 nhánh của chiến lược lỗi (đã chốt với người dùng): (1) thiếu cột bắt buộc trong header
 * -- FAIL toàn bộ job ngay, error_message là câu rõ ràng, KHÔNG phải stack trace kỹ thuật; (2) một
 * số dòng dữ liệu không hợp lệ nằm xen giữa các dòng hợp lệ -- SKIP các dòng đó, các dòng hợp lệ
 * vẫn được insert, job vẫn DONE, và 1 file báo cáo lỗi (row_number + reason) được upload thật lên
 * MinIO, result_location trỏ đúng tới file đó. Dùng Postgres + MinIO thật (không mock), gọi thẳng
 * bean ImportUserProcessor thật qua process() -- cùng khuôn với ExportUserCsvProcessorIT.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class ImportUserProcessorIT {

  private static final String BUCKET_NAME = "import-raw";

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
    registry.add("app.job.fallback-poller.interval", () -> "5m");
  }

  @Autowired private ImportUserProcessor processor;
  @Autowired private JobRepository jobRepository;
  @Autowired private FileAssetRepository fileAssetRepository;
  @Autowired private MinioStorageService minioStorageService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTables() {
    fileAssetRepository.deleteAll();
    jobRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM app_users WHERE email LIKE 'processor-it%@example.com'");
  }

  @Test
  void should_transitionToFailedWithClearErrorMessage_when_csvMissingRequiredColumn()
      throws Exception {
    String csvMissingEmailColumn =
        """
        full_name,status,role
        Alice,ACTIVE,MEMBER
        """;
    UUID jobId = UUID.randomUUID();
    UUID createdBy = UUID.randomUUID();
    UUID fileAssetId = seedCleanFileAsset(csvMissingEmailColumn, createdBy);
    jobRepository.save(
        new Job(
            jobId,
            JobType.IMPORT_USER,
            Map.of("fileAssetId", fileAssetId.toString()),
            null,
            null,
            null,
            createdBy));

    processor.process(
        new JobContext(jobId, JobType.IMPORT_USER, Map.of("fileAssetId", fileAssetId.toString())));

    Job job = pollUntilTerminal(jobId, Duration.ofSeconds(15));
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    // Message phải là câu tiếng Việt rõ ràng nêu đúng tên cột thiếu, không phải stack trace/tên
    // exception kỹ thuật (VD không chứa "com.company.platform" hay "Exception").
    assertThat(job.getErrorMessage()).isEqualTo("Thiếu cột bắt buộc: email");
    assertThat(job.getErrorMessage()).doesNotContain("Exception").doesNotContain("com.company");

    Long insertedCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_users WHERE email LIKE 'processor-it%@example.com'",
            Long.class);
    assertThat(insertedCount).isZero();
  }

  @Test
  void should_skipInvalidRowsAndUploadErrorReport_when_someRowsAreInvalid() throws Exception {
    String csvWithSomeInvalidRows =
        """
        email,full_name,status,role
        processor-it-1@example.com,Valid One,ACTIVE,MEMBER
        ,Missing Email,ACTIVE,MEMBER
        processor-it-2@example.com,Valid Two,ACTIVE,MEMBER
        processor-it-3@example.com,Valid Three,,MEMBER
        processor-it-4@example.com,Valid Four,ACTIVE,MEMBER
        """;
    UUID jobId = UUID.randomUUID();
    UUID createdBy = UUID.randomUUID();
    UUID fileAssetId = seedCleanFileAsset(csvWithSomeInvalidRows, createdBy);
    jobRepository.save(
        new Job(
            jobId,
            JobType.IMPORT_USER,
            Map.of("fileAssetId", fileAssetId.toString()),
            null,
            null,
            null,
            createdBy));

    processor.process(
        new JobContext(jobId, JobType.IMPORT_USER, Map.of("fileAssetId", fileAssetId.toString())));

    Job job = pollUntilTerminal(jobId, Duration.ofSeconds(15));
    assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    // 5 dòng dữ liệu, 2 dòng lỗi (thiếu email ở dòng 2, thiếu status ở dòng 4) -> 3 dòng hợp lệ.
    assertThat(job.getProcessedUnits()).isEqualTo(3);
    assertThat(job.getResultLocation()).isNotBlank();

    Long insertedCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_users WHERE email LIKE 'processor-it%@example.com'",
            Long.class);
    assertThat(insertedCount).isEqualTo(3);

    FileAsset reportAsset = fileAssetRepository.findByJobId(jobId).orElseThrow();
    assertThat(reportAsset.getCategoryCode()).isEqualTo("IMPORT_ERROR_REPORT");
    assertThat(reportAsset.getStorageStatus()).isEqualTo(StorageStatus.CLEAN);
    assertThat(job.getResultLocation()).isEqualTo(reportAsset.getObjectKey());

    String reportContent = downloadObject(reportAsset);
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().build();
    try (CSVParser parser = format.parse(new StringReader(reportContent))) {
      assertThat(parser.getHeaderNames()).containsExactly("row_number", "reason");
      List<CSVRecord> records = parser.getRecords();
      assertThat(records).hasSize(2);
      assertThat(records.get(0).get("row_number")).isEqualTo("2");
      assertThat(records.get(0).get("reason")).contains("email");
      assertThat(records.get(1).get("row_number")).isEqualTo("4");
      assertThat(records.get(1).get("reason")).contains("status");
    }
  }

  private UUID seedCleanFileAsset(String csvContent, UUID ownerId) throws Exception {
    UUID fileAssetId = UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();
    byte[] content = csvContent.getBytes(StandardCharsets.UTF_8);

    minioStorageService.ensureBucketExists(BUCKET_NAME, null);
    Path tempFile = Files.createTempFile("import-processor-it-", ".csv");
    try {
      Files.write(tempFile, content);
      minioStorageService.uploadFile(BUCKET_NAME, objectKey, tempFile, "text/csv");
    } finally {
      Files.deleteIfExists(tempFile);
    }

    FileAsset fileAsset =
        new FileAsset(
            fileAssetId,
            "IMPORT_RAW",
            BUCKET_NAME,
            objectKey,
            "users.csv",
            "text/csv",
            content.length,
            "USER",
            ownerId,
            ownerId,
            null);
    fileAssetRepository.save(fileAsset);
    // Đi thẳng UPLOADING -> CLEAN bằng JDBC thuần thay vì gọi casUpdateStorageStatus() trực tiếp
    // từ test (method đó @Modifying(flushAutomatically=true), cần transaction bao quanh, không có
    // sẵn khi gọi ngoài 1 @Transactional service method như FileUploadService bình thường vẫn làm).
    jdbcTemplate.update(
        "UPDATE file_assets SET storage_status = 'CLEAN' WHERE id = ?", fileAssetId);
    return fileAssetId;
  }

  private String downloadObject(FileAsset fileAsset) throws Exception {
    try (InputStream objectStream =
        minioStorageService.getObject(fileAsset.getBucketName(), fileAsset.getObjectKey())) {
      return new String(objectStream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private Job pollUntilTerminal(UUID jobId, Duration window) throws InterruptedException {
    long deadline = System.currentTimeMillis() + window.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Job job = jobRepository.findById(jobId).orElseThrow();
      if (job.getStatus() == JobStatus.DONE || job.getStatus() == JobStatus.FAILED) {
        return job;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Job " + jobId + " không đạt trạng thái cuối trong " + window);
  }
}
