package com.company.platform.file.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.net.http.HttpResponse;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end cho API internal downloadFileBytes (song song với /download-url, xem design doc
 * 2026-08-08-internal-download-bytes-api-design.md): upload -> confirm -> gọi
 * /api/internal/files/{id}/bytes với key đúng -> nội dung khớp file đã upload; gọi thiếu/sai key ->
 * 401, không lộ nội dung.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "app.internal-api.key=it-test-internal-api-key")
class FileInternalDownloadEndToEndIT {

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

  private static final byte[] FILE_CONTENT =
      "hello internal bytes".getBytes(StandardCharsets.UTF_8);
  private static final String CORRECT_KEY = "it-test-internal-api-key";
  private final ObjectMapper objectMapper = new ObjectMapper();

  private String categoryCode;

  @BeforeEach
  void seedCategory() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    categoryCode = "TEST_INTERNAL_" + suffix;
    fileCategoryRepository.save(
        new FileCategory(
            categoryCode,
            "internal-test-" + suffix,
            List.of("text/csv"),
            1_000_000L,
            false,
            true,
            null));
  }

  @Test
  void should_returnMatchingBytes_when_internalApiKeyCorrect_and_fileClean() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID fileAssetId = uploadAndConfirmFile(userId);

    byte[] responseBytes =
        mockMvc
            .perform(
                get("/api/internal/files/" + fileAssetId + "/bytes")
                    .header("X-Internal-Api-Key", CORRECT_KEY))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(responseBytes).isEqualTo(FILE_CONTENT);
  }

  @Test
  void should_return401_when_internalApiKeyMissing() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID fileAssetId = uploadAndConfirmFile(userId);

    mockMvc
        .perform(get("/api/internal/files/" + fileAssetId + "/bytes"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_return401_when_internalApiKeyWrong() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID fileAssetId = uploadAndConfirmFile(userId);

    mockMvc
        .perform(
            get("/api/internal/files/" + fileAssetId + "/bytes")
                .header("X-Internal-Api-Key", "wrong-key"))
        .andExpect(status().isUnauthorized());
  }

  private UUID uploadAndConfirmFile(UUID userId) throws Exception {
    String initiateResponseBody =
        mockMvc
            .perform(
                post("/api/files")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "categoryCode": "%s",
                          "originalFilename": "report.csv",
                          "mimeType": "text/csv",
                          "sizeBytes": %d,
                          "ownerType": "USER"
                        }
                        """
                            .formatted(categoryCode, FILE_CONTENT.length)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode initiateJson = objectMapper.readTree(initiateResponseBody);
    UUID fileAssetId = UUID.fromString(initiateJson.get("fileAssetId").asText());
    String uploadUrl = initiateJson.get("uploadUrl").asText();

    HttpClient httpClient = HttpClient.newHttpClient();
    HttpResponse<Void> putResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(uploadUrl))
                .PUT(BodyPublishers.ofByteArray(FILE_CONTENT))
                .build(),
            BodyHandlers.discarding());
    assertThat(putResponse.statusCode()).isEqualTo(200);

    mockMvc
        .perform(
            post("/api/files/" + fileAssetId + "/confirm")
                .header("X-User-Id", userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checksumSha256\": \"deadbeef\"}"))
        .andExpect(status().isOk());

    return fileAssetId;
  }
}
