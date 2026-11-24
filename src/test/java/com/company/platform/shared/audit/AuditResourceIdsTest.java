package com.company.platform.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditResourceIdsTest {

  @Test
  void should_returnSameUuid_when_sameCategoryCode() {
    assertThat(AuditResourceIds.forCategory("AVATAR"))
        .isEqualTo(AuditResourceIds.forCategory("AVATAR"))
        .isEqualTo(UUID.nameUUIDFromBytes("FILE_CATEGORY:AVATAR".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void should_differ_when_categoryVsSettingSameName() {
    assertThat(AuditResourceIds.forCategory("X")).isNotEqualTo(AuditResourceIds.forSetting("X"));
    assertThat(AuditResourceIds.forSetting("virus_scan.enabled"))
        .isEqualTo(
            UUID.nameUUIDFromBytes("SETTING:virus_scan.enabled".getBytes(StandardCharsets.UTF_8)));
  }
}
