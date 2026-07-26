package com.company.platform.export.csv;

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
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
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

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class ExportUserCsvProcessorIT {

  private static final int SEEDED_ROW_COUNT = 90;
  private static final int PAGE_SIZE = 25;

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
    registry.add("app.export.user-csv.page-size", () -> PAGE_SIZE);
  }

  @Autowired private ExportUserCsvProcessor processor;
  @Autowired private JobRepository jobRepository;
  @Autowired private FileAssetRepository fileAssetRepository;
  @Autowired private MinioStorageService minioStorageService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedAppUsersAndCleanJobs() {
    // file_assets.job_id có FK tới jobs.id -- phải xoá file_assets trước, nếu không deleteAll()
    // trên jobs sẽ vi phạm FK khi có nhiều @Test method để lại file_assets từ lần chạy trước.
    fileAssetRepository.deleteAll();
    jobRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM app_users");
    jdbcTemplate.update(
        """
        INSERT INTO app_users (id, email, full_name, phone_number, status, role, country, city, created_at, updated_at)
        SELECT gen_random_uuid(), 'user' || s || '@example.com', 'Demo User ' || s,
               '0900000' || lpad(s::text, 3, '0'), 'ACTIVE', 'MEMBER', 'VN', 'Hanoi', now(), now()
        FROM generate_series(1, %d) AS s
        """
            .formatted(SEEDED_ROW_COUNT));
  }

  @Test
  void should_exportAllSeededRowsToCleanFileAsset_when_processingJob() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID createdBy = UUID.randomUUID();
    jobRepository.save(
        new Job(jobId, JobType.EXPORT_USER_CSV, Map.of(), null, null, null, createdBy));

    processor.process(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    // process() giờ trả về gần như ngay lập tức (chỉ đợi CAS sang PROCESSING) -- doExecute()
    // chạy thật trên jobExecutionExecutor, phải poll tới trạng thái cuối thay vì assert ngay.
    Job job = pollUntilDone(jobId, Duration.ofSeconds(15));
    assertThat(job.getProcessedUnits()).isEqualTo(SEEDED_ROW_COUNT);

    FileAsset fileAsset = fileAssetRepository.findByJobId(jobId).orElseThrow();
    assertThat(fileAsset.getStorageStatus()).isEqualTo(StorageStatus.CLEAN);
    assertThat(fileAsset.getCategoryCode()).isEqualTo("EXPORT_RESULT");
    assertThat(fileAsset.getOwnerType()).isEqualTo("USER");
    assertThat(fileAsset.getOwnerId()).isEqualTo(createdBy);
    assertThat(fileAsset.getChecksumSha256()).hasSize(64);

    String csvContent = downloadObject(fileAsset);
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().build();
    try (CSVParser parser = format.parse(new StringReader(csvContent))) {
      assertThat(parser.getHeaderNames())
          .containsExactly(
              "id",
              "email",
              "full_name",
              "phone_number",
              "status",
              "role",
              "country",
              "city",
              "created_at",
              "updated_at");
      List<CSVRecord> records = parser.getRecords();
      assertThat(records).hasSize(SEEDED_ROW_COUNT);
    }
  }

  @Test
  void should_produceValidEmptyFileAndStillReachDone_when_noAppUsersExist() throws Exception {
    // Ghi đè seed của @BeforeEach về 0 dòng -- verify export không lỗi khi nguồn dữ liệu rỗng.
    jdbcTemplate.update("DELETE FROM app_users");

    UUID jobId = UUID.randomUUID();
    UUID createdBy = UUID.randomUUID();
    jobRepository.save(
        new Job(jobId, JobType.EXPORT_USER_CSV, Map.of(), null, null, null, createdBy));

    processor.process(new JobContext(jobId, JobType.EXPORT_USER_CSV, Map.of()));

    Job job = pollUntilDone(jobId, Duration.ofSeconds(15));
    assertThat(job.getProcessedUnits()).isZero();
    assertThat(job.getErrorMessage()).isNull();

    FileAsset fileAsset = fileAssetRepository.findByJobId(jobId).orElseThrow();
    assertThat(fileAsset.getStorageStatus()).isEqualTo(StorageStatus.CLEAN);
    // File rỗng vẫn có header CSV, không phải file 0 byte.
    assertThat(fileAsset.getSizeBytes()).isPositive();
    assertThat(fileAsset.getChecksumSha256()).hasSize(64);

    String csvContent = downloadObject(fileAsset);
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().build();
    try (CSVParser parser = format.parse(new StringReader(csvContent))) {
      assertThat(parser.getHeaderNames())
          .as("file rỗng vẫn phải có đúng header, chỉ thiếu data row")
          .containsExactly(
              "id",
              "email",
              "full_name",
              "phone_number",
              "status",
              "role",
              "country",
              "city",
              "created_at",
              "updated_at");
      List<CSVRecord> records = parser.getRecords();
      assertThat(records).isEmpty();
    }
  }

  private Job pollUntilDone(UUID jobId, Duration window) throws InterruptedException {
    long deadline = System.currentTimeMillis() + window.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Job job = jobRepository.findById(jobId).orElseThrow();
      if (job.getStatus() == JobStatus.DONE) {
        return job;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Job " + jobId + " không đạt DONE trong " + window);
  }

  private String downloadObject(FileAsset fileAsset) throws Exception {
    String url =
        minioStorageService.generatePresignedGetUrl(
            fileAsset.getBucketName(), fileAsset.getObjectKey(), Duration.ofMinutes(5));
    HttpClient httpClient = HttpClient.newHttpClient();
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }
}
