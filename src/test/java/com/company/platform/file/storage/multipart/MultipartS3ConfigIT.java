package com.company.platform.file.storage.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.util.UUID;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class MultipartS3ConfigIT {

  // Full Spring context (JPA + Liquibase) cần datasource để khởi động — bám theo pattern các
  // @SpringBootTest IT khác trong repo (MultipartColumnsIT, AvatarUploadDownloadEndToEndIT).
  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired S3Client s3MultipartClient;

  @Test
  void should_openAndAbortMultipartUpload_when_pointedAtMinio() throws Exception {
    String bucket = "cfg-it-" + UUID.randomUUID();
    MinioClient mc =
        MinioClient.builder()
            .endpoint(minio.getS3URL())
            .credentials(minio.getUserName(), minio.getPassword())
            .build();
    mc.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());

    String key = UUID.randomUUID().toString();
    String uploadId =
        s3MultipartClient
            .createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build())
            .uploadId();
    assertThat(uploadId).isNotBlank();

    s3MultipartClient.abortMultipartUpload(
        AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build());
  }
}
