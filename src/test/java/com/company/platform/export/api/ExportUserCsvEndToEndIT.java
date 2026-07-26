package com.company.platform.export.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.scheduler.JobScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.StatObjectResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end thật: POST /exports/users tạo job (PENDING) -> trigger JobScheduler thủ công (thay vì
 * đợi Kafka/fallback poller, đã có IT riêng cho 2 đường đó) -> job thật sự chạy qua
 * ExportUserCsvProcessor trên 1 thread nền trong khi test poll trạng thái từ thread chính, quan sát
 * PROCESSING trước khi tới DONE -> verify file CSV thật nằm trên MinIO (đọc lại qua
 * MinioStorageService, không mock) -> verify jobs.result_location trỏ đúng object_key đó -> verify
 * GET /exports/{id} trả về đúng thông tin cuối cùng. Không mock bất kỳ layer nào (Postgres + MinIO
 * thật qua Testcontainers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ExportUserCsvEndToEndIT {

  private static final int SEEDED_ROW_COUNT = 150;
  private static final int PAGE_SIZE = 20;

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  // JobSubmissionService.submit() gọi RateLimiterService (Redis token bucket) -- cần Redis thật,
  // không mock, để đi qua đúng đường HTTP thật POST /exports/users.
  @Container @ServiceConnection
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
    registry.add("app.export.user-csv.page-size", () -> PAGE_SIZE);
    // Test tự trigger JobScheduler thủ công -- đặt rất dài để fallback poller chắc chắn không
    // phải là bên xử lý job trong lúc test chạy.
    registry.add("app.job.fallback-poller.interval", () -> "5m");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JobRepository jobRepository;
  @Autowired private FileAssetRepository fileAssetRepository;
  @Autowired private JobScheduler jobScheduler;
  @Autowired private MinioStorageService minioStorageService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void seedAppUsersAndCleanJobs() {
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
  void should_exportUsersEndToEnd_throughRealHttpApiRealDispatchAndRealMinio() throws Exception {
    UUID adminUserId = UUID.randomUUID();

    // 1. POST /exports/users -> job tạo với status PENDING (ADMIN mới được export toàn bộ user).
    String createResponseBody =
        mockMvc
            .perform(
                post("/exports/users")
                    .header("X-User-Id", adminUserId.toString())
                    .header("X-User-Roles", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode createJson = objectMapper.readTree(createResponseBody);
    UUID jobId = UUID.fromString(createJson.get("id").asText());
    assertThat(createJson.get("status").asText()).isEqualTo("PENDING");
    assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
        .isEqualTo(JobStatus.PENDING);

    // 2. Trigger JobScheduler thủ công trên 1 thread nền, đồng thời poll trạng thái từ thread
    // chính để quan sát PENDING -> PROCESSING -> DONE thay vì chỉ thấy trạng thái cuối cùng.
    CompletableFuture<Boolean> dispatch =
        CompletableFuture.supplyAsync(jobScheduler::pollAndDispatch);
    Set<JobStatus> observedStatuses = EnumSet.noneOf(JobStatus.class);
    JobStatus finalStatus = pollUntilTerminal(jobId, observedStatuses, Duration.ofSeconds(15));
    assertThat(dispatch.get(5, TimeUnit.SECONDS))
        .as("pollAndDispatch phải nhận đúng job vừa tạo")
        .isTrue();

    assertThat(finalStatus).isEqualTo(JobStatus.DONE);
    assertThat(observedStatuses)
        .as(
            "phải quan sát được PROCESSING trước khi job đạt DONE, không nhảy thẳng PENDING -> DONE")
        .contains(JobStatus.PROCESSING);

    // 3. Job DONE, processedUnits khớp số dòng đã seed, result_location có giá trị.
    Job doneJob = jobRepository.findById(jobId).orElseThrow();
    assertThat(doneJob.getProcessedUnits()).isEqualTo(SEEDED_ROW_COUNT);
    assertThat(doneJob.getResultLocation()).isNotBlank();

    // 4. File thật đã lên MinIO -- đọc lại qua MinioStorageService (không mock), verify
    // result_location trong bảng jobs trỏ đúng object_key của file_assets tương ứng.
    FileAsset fileAsset = fileAssetRepository.findByJobId(jobId).orElseThrow();
    assertThat(doneJob.getResultLocation()).isEqualTo(fileAsset.getObjectKey());
    assertThat(fileAsset.getStorageStatus()).isEqualTo(StorageStatus.CLEAN);

    Optional<StatObjectResponse> stat =
        minioStorageService.statObject(fileAsset.getBucketName(), fileAsset.getObjectKey());
    assertThat(stat).as("object phải thực sự tồn tại trên MinIO").isPresent();
    assertThat(stat.get().size()).isEqualTo(fileAsset.getSizeBytes());

    // 5. GET /exports/{id} trả về đúng thông tin cuối cùng.
    String getResponseBody =
        mockMvc
            .perform(get("/exports/" + jobId).header("X-User-Id", adminUserId.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode getJson = objectMapper.readTree(getResponseBody);
    assertThat(getJson.get("id").asText()).isEqualTo(jobId.toString());
    assertThat(getJson.get("status").asText()).isEqualTo("DONE");
    assertThat(getJson.get("resultLocation").asText()).isEqualTo(fileAsset.getObjectKey());
    assertThat(getJson.get("processedUnits").asLong()).isEqualTo(SEEDED_ROW_COUNT);
  }

  private JobStatus pollUntilTerminal(UUID jobId, Set<JobStatus> observedStatuses, Duration timeout)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      JobStatus status = jobRepository.findById(jobId).orElseThrow().getStatus();
      observedStatuses.add(status);
      if (status == JobStatus.DONE || status == JobStatus.FAILED) {
        return status;
      }
      Thread.sleep(2);
    }
    throw new AssertionError(
        "Job "
            + jobId
            + " không đạt trạng thái cuối trong "
            + timeout
            + " (đã quan sát: "
            + observedStatuses
            + ")");
  }
}
