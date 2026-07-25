package com.company.platform.file.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MinioClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
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

  private MinioStorageService minioStorageService;

  @BeforeEach
  void setUp() {
    MinioClient minioClient =
        MinioClient.builder()
            .endpoint(MINIO_CONTAINER.getS3URL())
            .credentials(MINIO_CONTAINER.getUserName(), MINIO_CONTAINER.getPassword())
            .build();
    minioStorageService = new MinioStorageService(minioClient);
  }

  @Test
  void should_createBucket_when_bucketDoesNotExistYet() {
    String bucketName = "test-bucket-" + UUID.randomUUID();

    minioStorageService.ensureBucketExists(bucketName);
    minioStorageService.ensureBucketExists(bucketName);

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

    minioStorageService.ensureBucketExists(bucketName);

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
  void should_throwMinioStorageException_when_objectDoesNotExistOnDownload() throws IOException {
    String bucketName = "empty-" + UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();
    minioStorageService.ensureBucketExists(bucketName);

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
}
