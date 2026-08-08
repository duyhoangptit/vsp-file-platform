package com.company.platform.template.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class FileTemplateRepositoryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private FileTemplateRepository fileTemplateRepository;
  @Autowired private FileAssetRepository fileAssetRepository;

  private String category;
  private String templateCode;

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    category = "TEST_CATEGORY_" + suffix;
    templateCode = "TEST_CODE_" + suffix;
  }

  @Test
  void should_returnZero_when_noVersionExistsYet() {
    assertThat(fileTemplateRepository.findMaxVersion(category, templateCode)).isEqualTo(0);
  }

  @Test
  void should_returnHighestVersion_when_multipleVersionsExist() {
    saveTemplate(1);
    saveTemplate(2);
    saveTemplate(3);

    assertThat(fileTemplateRepository.findMaxVersion(category, templateCode)).isEqualTo(3);
  }

  @Test
  void should_returnLatestVersion_when_findingWithoutExplicitVersion() {
    saveTemplate(1);
    FileTemplate latest = saveTemplate(2);

    assertThat(
            fileTemplateRepository
                .findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc(
                    category, templateCode))
        .map(FileTemplate::getId)
        .contains(latest.getId());
  }

  @Test
  void should_returnRequestedVersion_when_versionSpecified() {
    FileTemplate v1 = saveTemplate(1);
    saveTemplate(2);

    assertThat(
            fileTemplateRepository.findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(
                category, templateCode, 1))
        .map(FileTemplate::getId)
        .contains(v1.getId());
  }

  @Test
  void should_returnEmpty_when_versionDoesNotExist() {
    saveTemplate(1);

    assertThat(
            fileTemplateRepository.findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(
                category, templateCode, 99))
        .isEmpty();
  }

  private FileTemplate saveTemplate(int version) {
    FileAsset fileAsset =
        fileAssetRepository.save(
            new FileAsset(
                UUID.randomUUID(),
                "FILE_TEMPLATE",
                "file-templates",
                UUID.randomUUID().toString(),
                "template.csv",
                "text/csv",
                100,
                "FILE_TEMPLATE",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null));
    return fileTemplateRepository.save(
        new FileTemplate(
            UUID.randomUUID(),
            category,
            templateCode,
            version,
            fileAsset.getId(),
            "mô tả test v" + version,
            UUID.randomUUID()));
  }
}
