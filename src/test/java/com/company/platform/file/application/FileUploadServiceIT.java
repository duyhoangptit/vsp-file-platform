package com.company.platform.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.FileVerificationFailedException;
import com.company.platform.file.domain.StorageStatus;
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
class FileUploadServiceIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void minioProperties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired private FileUploadService fileUploadService;
  @Autowired private FileCategoryRepository fileCategoryRepository;

  private String categoryCode;

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
  void should_transitionToClean_when_confirmingAfterRealUploadToMinio() throws Exception {
    String content = "hello minio " + UUID.randomUUID();
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

    FileUploadInitiation initiation =
        fileUploadService.initiateUpload(
            new InitiateFileUploadCommand(
                categoryCode,
                "avatar.png",
                "image/png",
                bytes.length,
                "USER",
                UUID.randomUUID(),
                UUID.randomUUID()));

    putToPresignedUrl(initiation.uploadUrl(), bytes);

    FileAsset confirmed = fileUploadService.confirmUpload(initiation.fileAssetId(), "fake-sha256");
    assertThat(confirmed.getStorageStatus()).isEqualTo(StorageStatus.CLEAN);
    assertThat(confirmed.getChecksumSha256()).isEqualTo("fake-sha256");

    FileAsset confirmedAgain =
        fileUploadService.confirmUpload(initiation.fileAssetId(), "fake-sha256");
    assertThat(confirmedAgain.getStorageStatus()).isEqualTo(StorageStatus.CLEAN);
  }

  @Test
  void should_throwFileVerificationFailedException_when_uploadedSizeDoesNotMatchDeclaredSize()
      throws Exception {
    FileUploadInitiation initiation =
        fileUploadService.initiateUpload(
            new InitiateFileUploadCommand(
                categoryCode,
                "avatar.png",
                "image/png",
                999,
                "USER",
                UUID.randomUUID(),
                UUID.randomUUID()));

    putToPresignedUrl(
        initiation.uploadUrl(), "wrong size content".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> fileUploadService.confirmUpload(initiation.fileAssetId(), "sha"))
        .isInstanceOf(FileVerificationFailedException.class);
  }

  @Test
  void should_throwFileValidationException_when_mimeTypeNotAllowedForCategory() {
    assertThatThrownBy(
            () ->
                fileUploadService.initiateUpload(
                    new InitiateFileUploadCommand(
                        categoryCode,
                        "malware.exe",
                        "application/octet-stream",
                        100,
                        "USER",
                        UUID.randomUUID(),
                        UUID.randomUUID())))
        .isInstanceOf(FileValidationException.class);
  }

  private void putToPresignedUrl(String url, byte[] content) throws Exception {
    HttpClient httpClient = HttpClient.newHttpClient();
    HttpResponse<Void> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(BodyPublishers.ofByteArray(content))
                .build(),
            BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);
  }
}
