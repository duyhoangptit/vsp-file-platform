package com.company.platform.file.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class AdminSchemaIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired FilePlatformSettingRepository settingRepository;
  @Autowired FileCategoryRepository categoryRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void should_seedVirusScanGlobalDisabled_when_migrated() {
    FilePlatformSetting setting =
        settingRepository.findById(FilePlatformSetting.VIRUS_SCAN_ENABLED).orElseThrow();
    assertThat(setting.booleanValue()).isFalse();
    assertThat(setting.getVersion()).isZero();
    assertThat(setting.getUpdatedBy()).isNull();
  }

  @Test
  void should_keepAllSeededCategoriesScanDisabled_when_migrated() {
    List<FileCategory> seeded =
        categoryRepository.findAllById(
            List.of("EXPORT_RESULT", "IMPORT_RAW", "IMPORT_ERROR_REPORT", "FILE_TEMPLATE"));
    assertThat(seeded).isNotEmpty();
    assertThat(seeded).allSatisfy(c -> assertThat(c.isRequireVirusScan()).isFalse());
    assertThat(seeded).allSatisfy(c -> assertThat(c.getVersion()).isZero());
  }

  @Test
  void should_rejectStaleVersion_when_categorySavedTwiceConcurrently() {
    String code = "ADM_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(code, "adm-bucket", List.of("text/csv"), 1000L, false, false, null, null));

    FileCategory first = categoryRepository.findById(code).orElseThrow();
    FileCategory second = categoryRepository.findById(code).orElseThrow();
    UUID actor = UUID.randomUUID();

    first.applyAdminUpdate(true, null, null, null, false, actor);
    categoryRepository.save(first);

    second.applyAdminUpdate(null, null, 2000L, null, false, actor);
    assertThatThrownBy(() -> categoryRepository.save(second))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    FileCategory reloaded = categoryRepository.findById(code).orElseThrow();
    assertThat(reloaded.isRequireVirusScan()).isTrue();
    assertThat(reloaded.getMaxSizeBytes()).isEqualTo(1000L);
    assertThat(reloaded.getVersion()).isEqualTo(1L);
    assertThat(reloaded.getUpdatedBy()).isEqualTo(actor);
  }

  @Test
  void should_clearRetention_when_clearRetentionRequested() {
    String code = "ADM_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(code, "adm-bucket", List.of("text/csv"), 1000L, false, false, 7, null));
    FileCategory loaded = categoryRepository.findById(code).orElseThrow();
    loaded.applyAdminUpdate(null, null, null, null, true, UUID.randomUUID());
    categoryRepository.save(loaded);
    assertThat(categoryRepository.findById(code).orElseThrow().getRetentionDays()).isNull();
  }

  @Test
  void should_haveAdminIndexes_when_migrated() {
    List<String> names =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE indexname IN"
                + " ('idx_file_assets_created_id', 'idx_audit_logs_type_action_created')",
            String.class);
    assertThat(names)
        .containsExactlyInAnyOrder(
            "idx_file_assets_created_id", "idx_audit_logs_type_action_created");
  }
}
