package com.company.platform.file.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.shared.audit.AuditLog;
import com.company.platform.shared.audit.AuditLogRepository;
import com.company.platform.shared.audit.AuditResult;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end: user A upload avatar -> confirm -> user A tự download được (nội dung thật khớp) ->
 * user B thử download file của A bị từ chối (403) -> audit_logs có bản ghi DENIED cho lần B bị
 * chặn. Dùng Postgres + MinIO thật (Testcontainers) để verify toàn bộ chuỗi HTTP + AOP + DB +
 * MinIO, không mock bất kỳ layer nào.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class AvatarUploadDownloadEndToEndIT {

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
  @Autowired private AuditLogRepository auditLogRepository;

  private static final byte[] AVATAR_CONTENT = "hello avatar".getBytes(StandardCharsets.UTF_8);
  private final ObjectMapper objectMapper = new ObjectMapper();

  private String categoryCode;

  @BeforeEach
  void seedAvatarCategory() {
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
  void should_allowOwnerDownload_and_denyOtherUserWithAuditLog_when_avatarUploadFlowCompletes()
      throws Exception {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();

    // 1. User A khởi tạo upload avatar, nhận presigned PUT URL.
    String initiateResponseBody =
        mockMvc
            .perform(
                post("/api/files")
                    .header("X-User-Id", userA.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "categoryCode": "%s",
                          "originalFilename": "avatar.png",
                          "mimeType": "image/png",
                          "sizeBytes": %d,
                          "ownerType": "USER"
                        }
                        """
                            .formatted(categoryCode, AVATAR_CONTENT.length)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode initiateJson = objectMapper.readTree(initiateResponseBody);
    UUID fileAssetId = UUID.fromString(initiateJson.get("fileAssetId").asText());
    String uploadUrl = initiateJson.get("uploadUrl").asText();

    // 2. Upload nội dung thật lên MinIO qua presigned PUT.
    HttpClient httpClient = HttpClient.newHttpClient();
    HttpResponse<Void> putResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(uploadUrl))
                .PUT(BodyPublishers.ofByteArray(AVATAR_CONTENT))
                .build(),
            BodyHandlers.discarding());
    assertThat(putResponse.statusCode()).isEqualTo(200);

    // 3. User A confirm upload -> storage_status chuyển CLEAN.
    mockMvc
        .perform(
            post("/api/files/" + fileAssetId + "/confirm")
                .header("X-User-Id", userA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checksumSha256\": \"deadbeef\"}"))
        .andExpect(status().isOk());

    // 4. User A tự download được, nội dung khớp file đã upload.
    String downloadResponseBody =
        mockMvc
            .perform(
                get("/api/files/" + fileAssetId + "/download-url")
                    .header("X-User-Id", userA.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String downloadUrl = objectMapper.readTree(downloadResponseBody).get("downloadUrl").asText();

    HttpResponse<String> getResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(getResponse.statusCode()).isEqualTo(200);
    assertThat(getResponse.body()).isEqualTo(new String(AVATAR_CONTENT, StandardCharsets.UTF_8));

    // 5. User B thử download file của A -> bị từ chối.
    mockMvc
        .perform(
            get("/api/files/" + fileAssetId + "/download-url")
                .header("X-User-Id", userB.toString()))
        .andExpect(status().isForbidden());

    // 6. audit_logs phải có bản ghi DENIED cho lần B bị chặn.
    boolean deniedLogRecorded =
        auditLogRepository.findAll().stream()
            .anyMatch(
                (AuditLog log) ->
                    log.getActorId().equals(userB)
                        && log.getResourceId().equals(fileAssetId)
                        && log.getResourceType().equals("FILE_ASSET")
                        && log.getResult() == AuditResult.DENIED);
    assertThat(deniedLogRecorded).isTrue();
  }
}
