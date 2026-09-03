package com.company.platform.file.storage.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class MultipartStorageServiceIT {

  // Full Spring context (JPA + Liquibase) cần datasource để khởi động — bám theo pattern các
  // @SpringBootTest IT khác trong repo (MultipartS3ConfigIT, MultipartColumnsIT).
  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired MultipartStorageService storage;

  private MinioClient minioClient;
  private String bucket;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() throws Exception {
    minioClient =
        MinioClient.builder()
            .endpoint(minio.getS3URL())
            .credentials(minio.getUserName(), minio.getPassword())
            .build();
    bucket = "mp-store-" + UUID.randomUUID();
    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
  }

  private String putPart(String url, byte[] body) throws Exception {
    HttpResponse<Void> resp =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).PUT(BodyPublishers.ofByteArray(body)).build(),
            BodyHandlers.discarding());
    assertThat(resp.statusCode()).isEqualTo(200);
    return resp.headers().firstValue("ETag").orElseThrow();
  }

  @Test
  void should_completeMultipartUpload_when_allPartsUploaded() throws Exception {
    String key = UUID.randomUUID().toString();
    byte[] p1 = new byte[6 * 1024 * 1024]; // 6 MiB (min non-final part is 5 MiB)
    byte[] p2 = "tail".getBytes();

    String uploadId = storage.createMultipartUpload(bucket, key, "application/octet-stream");
    String e1 =
        putPart(storage.presignUploadPart(bucket, key, uploadId, 1, Duration.ofMinutes(10)), p1);
    String e2 =
        putPart(storage.presignUploadPart(bucket, key, uploadId, 2, Duration.ofMinutes(10)), p2);

    List<MultipartStorageService.UploadedPart> parts = storage.listParts(bucket, key, uploadId);
    assertThat(parts)
        .extracting(MultipartStorageService.UploadedPart::partNumber)
        .containsExactly(1, 2);

    storage.completeMultipartUpload(
        bucket,
        key,
        uploadId,
        List.of(
            new MultipartStorageService.CompletedPartRef(1, e1),
            new MultipartStorageService.CompletedPartRef(2, e2)));

    long size =
        minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();
    assertThat(size).isEqualTo((long) p1.length + p2.length);
  }

  @Test
  void should_removeUpload_when_aborted() {
    String key = UUID.randomUUID().toString();
    String uploadId = storage.createMultipartUpload(bucket, key, "application/octet-stream");

    storage.abortMultipartUpload(bucket, key, uploadId);

    assertThat(storage.listParts(bucket, key, uploadId)).isEmpty();
  }
}
