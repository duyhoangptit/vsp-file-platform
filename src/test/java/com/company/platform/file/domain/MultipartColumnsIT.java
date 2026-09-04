package com.company.platform.file.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class MultipartColumnsIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired FileCategoryRepository categoryRepository;
  @Autowired FileAssetRepository assetRepository;

  @Test
  void should_persistMultipartPartSize_when_setOnCategory() {
    String code = "MP_CAT_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(
            code,
            "mp-bucket",
            List.of("text/csv"),
            5_000_000_000L,
            false,
            false,
            null,
            64L * 1024 * 1024));

    FileCategory reloaded = categoryRepository.findById(code).orElseThrow();
    assertThat(reloaded.getMultipartPartSizeBytes()).isEqualTo(64L * 1024 * 1024);
  }

  @Test
  void should_persistAndClearMultipartUploadId_when_setOnAsset() {
    String categoryCode = "MP_CAT_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(
            categoryCode,
            "mp-bucket",
            List.of("text/csv"),
            5_000_000_000L,
            false,
            false,
            null,
            null));

    FileAsset asset =
        new FileAsset(
            UUID.randomUUID(),
            categoryCode,
            "mp-bucket",
            UUID.randomUUID().toString(),
            "big.csv",
            "text/csv",
            0L,
            "USER",
            UUID.randomUUID(),
            UUID.randomUUID(),
            null);
    asset.attachMultipartUpload("upload-abc-123");
    assetRepository.save(asset);

    assertThat(assetRepository.findById(asset.getId()).orElseThrow().getMultipartUploadId())
        .isEqualTo("upload-abc-123");

    FileAsset reloaded = assetRepository.findById(asset.getId()).orElseThrow();
    reloaded.clearMultipartUpload();
    assetRepository.save(reloaded);

    assertThat(assetRepository.findById(asset.getId()).orElseThrow().getMultipartUploadId())
        .isNull();
  }
}
