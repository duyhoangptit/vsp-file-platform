package com.company.platform.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.InvalidPartNumberException;
import com.company.platform.file.domain.MultipartNotEnabledException;
import com.company.platform.file.domain.StorageStatus;
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
class MultipartUploadServiceIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired MultipartUploadService service;
  @Autowired FileCategoryRepository categoryRepository;
  @Autowired FileAssetRepository assetRepository;

  private String mpCategory;
  private String plainCategory;

  @BeforeEach
  void seed() {
    String s = UUID.randomUUID().toString().substring(0, 8);
    mpCategory = "MP_" + s;
    plainCategory = "PLAIN_" + s;
    categoryRepository.save(
        new FileCategory(
            mpCategory,
            "mp-" + s,
            List.of("text/csv"),
            500L * 1024 * 1024,
            false,
            false,
            null,
            64L * 1024 * 1024));
    categoryRepository.save(
        new FileCategory(
            plainCategory,
            "plain-" + s,
            List.of("text/csv"),
            1_000_000L,
            false,
            false,
            null,
            null));
  }

  private InitiateMultipartUploadCommand cmd(String category) {
    UUID user = UUID.randomUUID();
    return new InitiateMultipartUploadCommand(category, "big.csv", "text/csv", "USER", user, user);
  }

  @Test
  void should_createUploadingAssetWithUploadId_when_initiate() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    FileAsset asset = assetRepository.findById(init.fileAssetId()).orElseThrow();
    assertThat(asset.getStorageStatus()).isEqualTo(StorageStatus.UPLOADING);
    assertThat(asset.getMultipartUploadId()).isNotBlank();
    assertThat(init.partSizeBytes()).isEqualTo(64L * 1024 * 1024);
    assertThat(init.maxParts()).isEqualTo(8); // ceil(500MiB / 64MiB)
  }

  @Test
  void should_reject_when_categoryHasNoPartSize() {
    assertThatThrownBy(() -> service.initiateMultipart(cmd(plainCategory)))
        .isInstanceOf(MultipartNotEnabledException.class);
  }

  @Test
  void should_returnOnePresignedUrlPerRequestedPart_when_generatePartUrls() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    List<PartUrl> urls = service.generatePartUrls(init.fileAssetId(), List.of(1, 2, 3));

    assertThat(urls).extracting(PartUrl::partNumber).containsExactly(1, 2, 3);
    assertThat(urls).allSatisfy(u -> assertThat(u.url()).startsWith("http"));
  }

  @Test
  void should_reject_when_partNumberBeyondMaxParts() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    assertThatThrownBy(() -> service.generatePartUrls(init.fileAssetId(), List.of(9)))
        .isInstanceOf(InvalidPartNumberException.class);
  }
}
