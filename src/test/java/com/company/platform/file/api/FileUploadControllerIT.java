package com.company.platform.file.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verify @RequiresAccess thực sự chặn request qua toàn bộ Spring context thật (không
 * phải @WebMvcTest slice — slice mặc định KHÔNG bật AopAutoConfiguration nên aspect không chạy, xem
 * FileUploadControllerTest). Test này dùng Postgres + MinIO thật để chắc chắn AOP + DB owner lookup
 * hoạt động đúng trong app thật, không chỉ trong slice test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class FileUploadControllerIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void minioProperties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private FileCategoryRepository fileCategoryRepository;

  private String categoryCode;
  private static final byte[] CONTENT = "hello minio".getBytes(StandardCharsets.UTF_8);

  @BeforeEach
  void seedCategory() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    categoryCode = "TEST_AVATAR_" + suffix;
    fileCategoryRepository.save(
        new FileCategory(
            categoryCode,
            "avatar-test-" + suffix,
            List.of("image/png"),
            1_000_000L,
            false,
            true,
            null));
  }

  @Test
  void should_return403_when_confirmingSomeoneElsesUpload() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    UUID fileAssetId = initiateAndUpload(owner);

    mockMvc
        .perform(
            post("/api/files/" + fileAssetId + "/confirm")
                .header("X-User-Id", stranger.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checksumSha256\": \"deadbeef\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_return200_when_ownerConfirmsTheirOwnUpload() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID fileAssetId = initiateAndUpload(owner);

    mockMvc
        .perform(
            post("/api/files/" + fileAssetId + "/confirm")
                .header("X-User-Id", owner.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checksumSha256\": \"deadbeef\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void should_return200_when_adminConfirmsSomeoneElsesUpload() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID fileAssetId = initiateAndUpload(owner);

    mockMvc
        .perform(
            post("/api/files/" + fileAssetId + "/confirm")
                .header("X-User-Id", admin.toString())
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checksumSha256\": \"deadbeef\"}"))
        .andExpect(status().isOk());
  }

  /** Gọi initiate qua HTTP thật, rồi PUT nội dung thật lên presigned URL trả về. */
  private UUID initiateAndUpload(UUID userId) throws Exception {
    String requestBody =
        """
        {
          "categoryCode": "%s",
          "originalFilename": "avatar.png",
          "mimeType": "image/png",
          "sizeBytes": %d,
          "ownerType": "USER"
        }
        """
            .formatted(categoryCode, CONTENT.length);

    String responseBody =
        mockMvc
            .perform(
                post("/api/files")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode json = new ObjectMapper().readTree(responseBody);
    UUID fileAssetId = UUID.fromString(json.get("fileAssetId").asText());
    String uploadUrl = json.get("uploadUrl").asText();

    HttpClient httpClient = HttpClient.newHttpClient();
    httpClient.send(
        HttpRequest.newBuilder(URI.create(uploadUrl))
            .PUT(BodyPublishers.ofByteArray(CONTENT))
            .build(),
        BodyHandlers.discarding());

    return fileAssetId;
  }
}
