package com.company.platform.file.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.StorageStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * End-to-end: HTTP (MockMvc) -> real MultipartUploadService -> real MinIO (part PUTs go straight to
 * MinIO via a raw java.net.http.HttpClient) -> real Postgres. Mirrors {@code
 * FileUploadControllerIT}. Covers the full /api/files/multipart lifecycle: initiate, part URLs,
 * direct part upload, list parts (resume), complete, abort, and the two main reject paths
 * (oversize, non-multipart category).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class MultipartUploadEndToEndIT {

  private static final int MIB = 1024 * 1024;
  private static final long PART_SIZE = 5L * MIB;
  private static final long MAX_SIZE = 50L * MIB;

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
  @Autowired private FileAssetRepository fileAssetRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newHttpClient();

  private String multipartCategory;

  @BeforeEach
  void seedCategories() {
    multipartCategory = saveCategory(PART_SIZE, MAX_SIZE);
  }

  private String saveCategory(Long partSizeBytes, long maxSizeBytes) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String code = "MP_E2E_" + suffix;
    fileCategoryRepository.save(
        new FileCategory(
            code,
            "mp-e2e-" + suffix,
            List.of("text/csv"),
            maxSizeBytes,
            false,
            false,
            null,
            partSizeBytes));
    return code;
  }

  @Test
  void should_completeUpload_when_happyPath() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID fileAssetId = initiate(owner, multipartCategory);

    Map<Integer, String> urls = requestPartUrls(owner, fileAssetId, List.of(1, 2));
    byte[] part1 = filledBytes((int) PART_SIZE, (byte) 'a');
    byte[] part2 = "trailing bytes".getBytes();
    String etag1 = putPart(urls.get(1), part1);
    String etag2 = putPart(urls.get(2), part2);

    mockMvc
        .perform(
            post("/api/files/multipart/" + fileAssetId + "/complete")
                .header("X-User-Id", owner.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(completeBody(Map.of(1, etag1, 2, etag2), "cafebabe")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.storageStatus").value("CLEAN"));

    FileAsset row = fileAssetRepository.findById(fileAssetId).orElseThrow();
    assertThat(row.getMultipartUploadId()).isNull();
    assertThat(row.getSizeBytes()).isEqualTo((long) part1.length + part2.length);
    assertThat(row.getStorageStatus()).isEqualTo(StorageStatus.CLEAN);
  }

  @Test
  void should_resume_when_reRequestingUrlsAndListingParts() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID fileAssetId = initiate(owner, multipartCategory);

    byte[] part1 = filledBytes((int) PART_SIZE, (byte) 'a');
    String etag1 = putPart(requestPartUrls(owner, fileAssetId, List.of(1)).get(1), part1);

    mockMvc
        .perform(
            get("/api/files/multipart/" + fileAssetId + "/parts")
                .header("X-User-Id", owner.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts.length()").value(1))
        .andExpect(jsonPath("$.parts[0].partNumber").value(1))
        .andExpect(jsonPath("$.parts[0].etag").isNotEmpty())
        .andExpect(jsonPath("$.parts[0].sizeBytes").value(PART_SIZE));

    byte[] part2 = "the rest".getBytes();
    String etag2 = putPart(requestPartUrls(owner, fileAssetId, List.of(2)).get(2), part2);

    mockMvc
        .perform(
            post("/api/files/multipart/" + fileAssetId + "/complete")
                .header("X-User-Id", owner.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(completeBody(Map.of(1, etag1, 2, etag2), "cafebabe")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.storageStatus").value("CLEAN"));
  }

  @Test
  void should_return403_when_strangerRequestsPartUrls() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    UUID fileAssetId = initiate(owner, multipartCategory);

    mockMvc
        .perform(
            post("/api/files/multipart/" + fileAssetId + "/part-urls")
                .header("X-User-Id", stranger.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"partNumbers\":[1]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_return204AndAbort_when_ownerAborts() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID fileAssetId = initiate(owner, multipartCategory);

    mockMvc
        .perform(
            post("/api/files/multipart/" + fileAssetId + "/abort")
                .header("X-User-Id", owner.toString()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/files/multipart/" + fileAssetId + "/parts")
                .header("X-User-Id", owner.toString()))
        .andExpect(status().isConflict());

    FileAsset row = fileAssetRepository.findById(fileAssetId).orElseThrow();
    assertThat(row.getDeletedAt()).isNotNull();
    assertThat(row.getMultipartUploadId()).isNull();
  }

  @Test
  void should_rejectComplete_when_objectExceedsCategoryMax() throws Exception {
    UUID owner = UUID.randomUUID();
    String tinyMaxCategory = saveCategory(PART_SIZE, 1_000L);
    UUID fileAssetId = initiate(owner, tinyMaxCategory);

    byte[] part1 = filledBytes((int) PART_SIZE, (byte) 'a');
    String etag1 = putPart(requestPartUrls(owner, fileAssetId, List.of(1)).get(1), part1);

    mockMvc
        .perform(
            post("/api/files/multipart/" + fileAssetId + "/complete")
                .header("X-User-Id", owner.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(completeBody(Map.of(1, etag1), "cafebabe")))
        .andExpect(status().isUnprocessableEntity());

    FileAsset row = fileAssetRepository.findById(fileAssetId).orElseThrow();
    assertThat(row.getStorageStatus()).isNotEqualTo(StorageStatus.CLEAN);
    assertThat(row.getStorageStatus()).isEqualTo(StorageStatus.UPLOADING);
  }

  @Test
  void should_return409_when_initiatingOnNonMultipartCategory() throws Exception {
    UUID owner = UUID.randomUUID();
    String plainCategory = saveCategory(null, MAX_SIZE);

    mockMvc
        .perform(
            post("/api/files/multipart")
                .header("X-User-Id", owner.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(initiateBody(plainCategory)))
        .andExpect(status().isConflict());
  }

  // --- helpers -------------------------------------------------------------

  private String initiateBody(String categoryCode) {
    return """
        {
          "categoryCode": "%s",
          "originalFilename": "big.csv",
          "mimeType": "text/csv",
          "ownerType": "USER"
        }
        """
        .formatted(categoryCode);
  }

  private UUID initiate(UUID userId, String categoryCode) throws Exception {
    String responseBody =
        mockMvc
            .perform(
                post("/api/files/multipart")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(initiateBody(categoryCode)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(objectMapper.readTree(responseBody).get("fileAssetId").asText());
  }

  private Map<Integer, String> requestPartUrls(UUID userId, UUID fileAssetId, List<Integer> parts)
      throws Exception {
    ArrayNode partNumbers = objectMapper.createArrayNode();
    parts.forEach(partNumbers::add);
    ObjectNode body = objectMapper.createObjectNode();
    body.set("partNumbers", partNumbers);

    String responseBody =
        mockMvc
            .perform(
                post("/api/files/multipart/" + fileAssetId + "/part-urls")
                    .header("X-User-Id", userId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Map<Integer, String> urls = new LinkedHashMap<>();
    for (JsonNode node : objectMapper.readTree(responseBody).get("urls")) {
      urls.put(node.get("partNumber").asInt(), node.get("url").asText());
    }
    return urls;
  }

  private String putPart(String url, byte[] body) throws Exception {
    HttpResponse<Void> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.headers().firstValue("ETag").orElseThrow();
  }

  private String completeBody(Map<Integer, String> partEtags, String checksumSha256)
      throws Exception {
    ArrayNode parts = objectMapper.createArrayNode();
    partEtags.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            e -> {
              ObjectNode part = objectMapper.createObjectNode();
              part.put("partNumber", e.getKey());
              part.put("etag", e.getValue());
              parts.add(part);
            });
    ObjectNode body = objectMapper.createObjectNode();
    body.set("parts", parts);
    body.put("checksumSha256", checksumSha256);
    return objectMapper.writeValueAsString(body);
  }

  private static byte[] filledBytes(int size, byte value) {
    byte[] bytes = new byte[size];
    java.util.Arrays.fill(bytes, value);
    return bytes;
  }
}
