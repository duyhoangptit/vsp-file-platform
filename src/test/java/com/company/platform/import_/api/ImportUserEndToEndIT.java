package com.company.platform.import_.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.scheduler.JobScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
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
 * End-to-end thật, cùng khuôn với ExportUserCsvEndToEndIT: upload CSV thật qua POST /api/files
 * (category IMPORT_RAW, seed sẵn qua Liquibase) + presigned PUT thật -> confirm -> POST
 * /imports/users tạo job -> trigger JobScheduler thủ công -> job chạy qua ImportUserProcessor trên
 * thread nền, đọc lại file thật từ MinIO -> verify rows đã insert vào app_users. Không mock layer
 * nào (Postgres + MinIO + Redis thật qua Testcontainers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ImportUserEndToEndIT {

  private static final String CSV_CONTENT =
      """
      email,full_name,phone_number,status,role,country,city
      import1@example.com,Import One,0900000001,ACTIVE,MEMBER,VN,Hanoi
      import2@example.com,Import Two,0900000002,ACTIVE,MEMBER,VN,Hanoi
      import3@example.com,Import Three,,ACTIVE,ADMIN,US,
      import4@example.com,Import Four,,ACTIVE,MEMBER,VN,Hanoi
      import5@example.com,Import Five,,ACTIVE,MEMBER,VN,Hanoi
      """;
  private static final int EXPECTED_ROW_COUNT = 5;

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @Container @ServiceConnection
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
    // Batch nhỏ hơn số dòng seed để chắc chắn đi qua nhánh nhiều batch, không chỉ 1 batch duy nhất.
    registry.add("app.import.user-csv.batch-size", () -> 2);
    registry.add("app.job.fallback-poller.interval", () -> "5m");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JobRepository jobRepository;
  @Autowired private JobScheduler jobScheduler;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void cleanJobsAndAppUsers() {
    jobRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM app_users WHERE email LIKE 'import%@example.com'");
  }

  @Test
  void should_importUsersEndToEnd_throughRealHttpApiRealDispatchAndRealMinio() throws Exception {
    UUID ownerId = UUID.randomUUID();

    UUID fileAssetId = uploadAndConfirmCsv(ownerId);

    // POST /imports/users -> job tạo với status PENDING (owner của file mới được submit import).
    String createResponseBody =
        mockMvc
            .perform(
                post("/imports/users")
                    .header("X-User-Id", ownerId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fileAssetId\": \"" + fileAssetId + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode createJson = objectMapper.readTree(createResponseBody);
    UUID jobId = UUID.fromString(createJson.get("id").asText());
    assertThat(createJson.get("status").asText()).isEqualTo("PENDING");

    // Trigger JobScheduler thủ công trên 1 thread nền, poll trạng thái từ thread chính.
    CompletableFuture<Boolean> dispatch =
        CompletableFuture.supplyAsync(jobScheduler::pollAndDispatch);
    Set<JobStatus> observedStatuses = EnumSet.noneOf(JobStatus.class);
    JobStatus finalStatus = pollUntilTerminal(jobId, observedStatuses, Duration.ofSeconds(15));
    assertThat(dispatch.get(5, TimeUnit.SECONDS))
        .as("pollAndDispatch phải nhận đúng job vừa tạo")
        .isTrue();

    assertThat(finalStatus).isEqualTo(JobStatus.DONE);

    Job doneJob = jobRepository.findById(jobId).orElseThrow();
    assertThat(doneJob.getProcessedUnits()).isEqualTo(EXPECTED_ROW_COUNT);
    assertThat(doneJob.getResultLocation()).isNull();

    // Rows đã thực sự insert vào app_users qua đúng pipeline (không mock DB layer).
    Long importedCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_users WHERE email LIKE 'import%@example.com'", Long.class);
    assertThat(importedCount).isEqualTo(EXPECTED_ROW_COUNT);

    // GET /imports/{id} trả về đúng thông tin cuối cùng.
    String getResponseBody =
        mockMvc
            .perform(get("/imports/" + jobId).header("X-User-Id", ownerId.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode getJson = objectMapper.readTree(getResponseBody);
    assertThat(getJson.get("status").asText()).isEqualTo("DONE");
    assertThat(getJson.get("processedUnits").asLong()).isEqualTo(EXPECTED_ROW_COUNT);
  }

  @Test
  void should_return403_when_strangerSubmitsImportForSomeoneElsesFile() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    UUID fileAssetId = uploadAndConfirmCsv(owner);

    mockMvc
        .perform(
            post("/imports/users")
                .header("X-User-Id", stranger.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileAssetId\": \"" + fileAssetId + "\"}"))
        .andExpect(status().isForbidden());
  }

  /** Upload CSV thật qua initiate + presigned PUT + confirm, category IMPORT_RAW đã seed sẵn. */
  private UUID uploadAndConfirmCsv(UUID ownerId) throws Exception {
    byte[] content = CSV_CONTENT.getBytes(StandardCharsets.UTF_8);
    String initiateRequestBody =
        """
        {
          "categoryCode": "IMPORT_RAW",
          "originalFilename": "users.csv",
          "mimeType": "text/csv",
          "sizeBytes": %d,
          "ownerType": "USER"
        }
        """
            .formatted(content.length);

    String initiateResponseBody =
        mockMvc
            .perform(
                post("/api/files")
                    .header("X-User-Id", ownerId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(initiateRequestBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode initiateJson = objectMapper.readTree(initiateResponseBody);
    UUID fileAssetId = UUID.fromString(initiateJson.get("fileAssetId").asText());
    String uploadUrl = initiateJson.get("uploadUrl").asText();

    HttpClient httpClient = HttpClient.newHttpClient();
    httpClient.send(
        HttpRequest.newBuilder(URI.create(uploadUrl))
            .PUT(BodyPublishers.ofByteArray(content))
            .build(),
        BodyHandlers.discarding());

    mockMvc
        .perform(
            post("/api/files/" + fileAssetId + "/confirm")
                .header("X-User-Id", ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checksumSha256\": \"deadbeef\"}"))
        .andExpect(status().isOk());

    return fileAssetId;
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
