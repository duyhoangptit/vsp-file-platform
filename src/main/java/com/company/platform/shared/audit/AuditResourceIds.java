package com.company.platform.shared.audit;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@code audit_logs.resource_id} là UUID NOT NULL, nhưng category dùng {@code code} và setting dùng
 * {@code setting_key} (string) — sinh UUID name-based (v3) deterministic để lọc theo resource được;
 * giá trị gốc ghi kèm ở {@code metadata.resourceKey}.
 */
public final class AuditResourceIds {

  private AuditResourceIds() {}

  public static UUID forCategory(String code) {
    return nameBased("FILE_CATEGORY:" + code);
  }

  public static UUID forSetting(String key) {
    return nameBased("SETTING:" + key);
  }

  private static UUID nameBased(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }
}
