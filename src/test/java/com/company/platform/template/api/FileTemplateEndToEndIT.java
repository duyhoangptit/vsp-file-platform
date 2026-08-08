package com.company.platform.template.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end: ADMIN upload version 1 -> upload version 2 -> user thường (không ADMIN) download được
 * bản mới nhất (mặc định = version 2) và cả bản version=1 tường minh -> user thường bị từ chối khi
 * cố upload -> category/templateCode không tồn tại trả 404. Dùng Postgres + MinIO thật
 * (Testcontainers), không mock layer nào.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class FileTemplateEndToEndIT {

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

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void should_uploadTwoVersions_and_downloadLatestOrSpecificVersion_and_denyNonAdminUpload()
      throws Exception {
    UUID adminId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String category = "MEMBER_" + suffix;
    String templateCode = "MEMBER_IMPORT_" + suffix;

    byte[] v1Content = "version-1-content".getBytes(StandardCharsets.UTF_8);
    byte[] v2Content = "version-2-content".getBytes(StandardCharsets.UTF_8);

    uploadTemplateVersion(adminId, category, templateCode, v1Content, "template v1");
    uploadTemplateVersion(adminId, category, templateCode, v2Content, "template v2");

    String latestUrl = downloadUrl(memberId, category, templateCode, null);
    assertThat(fetchContent(latestUrl)).isEqualTo(new String(v2Content, StandardCharsets.UTF_8));

    String v1Url = downloadUrl(memberId, category, templateCode, 1);
    assertThat(fetchContent(v1Url)).isEqualTo(new String(v1Content, StandardCharsets.UTF_8));

    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .header("X-User-Id", memberId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "category": "%s",
                      "templateCode": "%s",
                      "originalFilename": "hack.xlsx",
                      "mimeType": "text/csv",
                      "sizeBytes": 10
                    }
                    """
                        .formatted(category, templateCode)))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/templates/download-url")
                .header("X-User-Id", memberId.toString())
                .param("category", "KHONG_TON_TAI")
                .param("templateCode", "KHONG_TON_TAI"))
        .andExpect(status().isNotFound());
  }

  private void uploadTemplateVersion(
      UUID adminId, String category, String templateCode, byte[] content, String description)
      throws Exception {
    String initiateBody =
        mockMvc
            .perform(
                post("/api/templates/upload/initiate")
                    .header("X-User-Id", adminId.toString())
                    .header("X-User-Roles", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "category": "%s",
                          "templateCode": "%s",
                          "originalFilename": "template.csv",
                          "mimeType": "text/csv",
                          "sizeBytes": %d
                        }
                        """
                            .formatted(category, templateCode, content.length)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode initiateJson = objectMapper.readTree(initiateBody);
    String fileAssetId = initiateJson.get("fileAssetId").asText();
    String uploadUrl = initiateJson.get("uploadUrl").asText();

    HttpResponse<Void> putResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(uploadUrl))
                .PUT(BodyPublishers.ofByteArray(content))
                .build(),
            BodyHandlers.discarding());
    assertThat(putResponse.statusCode()).isEqualTo(200);

    mockMvc
        .perform(
            post("/api/templates/upload/confirm")
                .header("X-User-Id", adminId.toString())
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fileAssetId": "%s",
                      "category": "%s",
                      "templateCode": "%s",
                      "checksumSha256": "fake-sha256",
                      "description": "%s"
                    }
                    """
                        .formatted(fileAssetId, category, templateCode, description)))
        .andExpect(status().isOk());
  }

  private String downloadUrl(UUID userId, String category, String templateCode, Integer version)
      throws Exception {
    MockHttpServletRequestBuilder requestBuilder =
        get("/api/templates/download-url")
            .header("X-User-Id", userId.toString())
            .param("category", category)
            .param("templateCode", templateCode);
    if (version != null) {
      requestBuilder = requestBuilder.param("version", version.toString());
    }
    String body =
        mockMvc
            .perform(requestBuilder)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).get("downloadUrl").asText();
  }

  private String fetchContent(String url) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }
}
