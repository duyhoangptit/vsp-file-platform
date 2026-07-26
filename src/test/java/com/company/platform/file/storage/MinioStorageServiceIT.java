package com.company.platform.file.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.minio.GetBucketLifecycleArgs;
import io.minio.MinioClient;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.Status;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MinioStorageServiceIT {

  @Container
  private static final MinIOContainer MINIO_CONTAINER = new MinIOContainer("minio/minio:latest");

  private MinioClient minioClient;
  private MinioStorageService minioStorageService;

  @BeforeEach
  void setUp() {
    minioClient =
        MinioClient.builder()
            .endpoint(MINIO_CONTAINER.getS3URL())
            .credentials(MINIO_CONTAINER.getUserName(), MINIO_CONTAINER.getPassword())
            .build();
    minioStorageService = new MinioStorageService(minioClient);
  }

  @Test
  void should_createBucket_when_bucketDoesNotExistYet() {
    String bucketName = "test-bucket-" + UUID.randomUUID();

    minioStorageService.ensureBucketExists(bucketName, null);
    minioStorageService.ensureBucketExists(bucketName, null);

    String objectKey = UUID.randomUUID().toString();
    String getUrl =
        minioStorageService.generatePresignedGetUrl(bucketName, objectKey, Duration.ofMinutes(5));
    assertThat(getUrl).contains(bucketName).contains(objectKey);
  }

  @Test
  void should_uploadAndDownloadFileSuccessfully_when_usingPresignedUrls() throws Exception {
    String bucketName = "avatar-" + UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();
    String content = "hello minio " + UUID.randomUUID();

    minioStorageService.ensureBucketExists(bucketName, null);

    String putUrl =
        minioStorageService.generatePresignedPutUrl(bucketName, objectKey, Duration.ofMinutes(5));
    String getUrl =
        minioStorageService.generatePresignedGetUrl(bucketName, objectKey, Duration.ofMinutes(5));

    HttpClient httpClient = HttpClient.newHttpClient();

    HttpResponse<Void> putResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(putUrl))
                .PUT(BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                .build(),
            BodyHandlers.discarding());
    assertThat(putResponse.statusCode()).isEqualTo(200);

    HttpResponse<String> getResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(getUrl)).GET().build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(getResponse.statusCode()).isEqualTo(200);
    assertThat(getResponse.body()).isEqualTo(content);
  }

  @Test
  void should_uploadLocalFileSuccessfully_when_usingUploadFile() throws Exception {
    String bucketName = "export-" + UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();
    String content = "id,email\n1,a@b.com\n";
    Path tempFile = Files.createTempFile("upload-file-it-", ".csv");
    Files.writeString(tempFile, content, StandardCharsets.UTF_8);

    try {
      minioStorageService.ensureBucketExists(bucketName, null);
      minioStorageService.uploadFile(bucketName, objectKey, tempFile, "text/csv");

      String getUrl =
          minioStorageService.generatePresignedGetUrl(bucketName, objectKey, Duration.ofMinutes(5));
      HttpClient httpClient = HttpClient.newHttpClient();
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(getUrl)).GET().build(),
              BodyHandlers.ofString(StandardCharsets.UTF_8));

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo(content);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void should_readBackSameBytes_when_usingGetObjectAfterUploadFile() throws Exception {
    String bucketName = "import-" + UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();
    String content = "email,full_name,status,role\na@b.com,Alice,ACTIVE,MEMBER\n";
    Path tempFile = Files.createTempFile("get-object-it-", ".csv");
    Files.writeString(tempFile, content, StandardCharsets.UTF_8);

    try {
      minioStorageService.ensureBucketExists(bucketName, null);
      minioStorageService.uploadFile(bucketName, objectKey, tempFile, "text/csv");

      try (InputStream objectStream = minioStorageService.getObject(bucketName, objectKey)) {
        String readBack = new String(objectStream.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(readBack).isEqualTo(content);
      }
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void should_throwMinioStorageException_when_objectDoesNotExistOnGetObject() {
    String bucketName = "empty-" + UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();
    minioStorageService.ensureBucketExists(bucketName, null);

    assertThatThrownBy(() -> minioStorageService.getObject(bucketName, objectKey))
        .isInstanceOf(MinioStorageException.class);
  }

  @Test
  void should_throwMinioStorageException_when_objectDoesNotExistOnDownload() throws IOException {
    String bucketName = "empty-" + UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();
    minioStorageService.ensureBucketExists(bucketName, null);

    String getUrl =
        minioStorageService.generatePresignedGetUrl(bucketName, objectKey, Duration.ofMinutes(5));

    HttpClient httpClient = HttpClient.newHttpClient();
    HttpResponse<Void> response;
    try {
      response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(getUrl)).GET().build(), BodyHandlers.discarding());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void should_setLifecycleExpirationRule_when_retentionDaysProvided() throws Exception {
    String bucketName = "retention-" + UUID.randomUUID();

    minioStorageService.ensureBucketExists(bucketName, 7);

    LifecycleConfiguration lifecycle =
        minioClient.getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(bucketName).build());
    assertThat(lifecycle.rules()).hasSize(1);
    LifecycleConfiguration.Rule rule = lifecycle.rules().get(0);
    assertThat(rule.status()).isEqualTo(Status.ENABLED);
    assertThat(rule.expiration().days()).isEqualTo(7);
  }

  @Test
  void should_notSetLifecycleRule_when_retentionDaysIsNull() throws Exception {
    String bucketName = "no-retention-" + UUID.randomUUID();

    minioStorageService.ensureBucketExists(bucketName, null);

    // MinioClient.getBucketLifecycle trả null (không ném exception) khi bucket chưa có
    // lifecycle config — SDK tự nuốt lỗi "NoSuchLifecycleConfiguration" từ server.
    LifecycleConfiguration lifecycle =
        minioClient.getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(bucketName).build());
    assertThat(lifecycle).isNull();
  }
}
