package com.company.platform.file.api.admin;

import com.company.platform.file.application.admin.UpdateFileCategoryCommand;
import com.company.platform.file.domain.FileValidationException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Body PATCH đọc dạng {@code Map}: field vắng mặt = không đổi. Chỉ chấp nhận {@code version} (bắt
 * buộc, số nguyên), {@code requireVirusScan}, {@code allowedMimeTypes}, {@code maxSizeBytes}; field
 * khác (kể cả {@code bucketName}, {@code retentionDays}) → 400.
 *
 * <p>{@code retentionDays} tạm chưa cho sửa: lifecycle rule MinIO chỉ set lúc tạo bucket và áp cho
 * cả bucket, {@code expires_at} của file cũ không được tính lại, CleanupService bỏ qua category
 * retention null — đổi qua API sẽ làm DB và storage lệch nhau. Cần thiết kế riêng.
 */
final class UpdateFileCategoryRequest {

  private UpdateFileCategoryRequest() {}

  static final Set<String> EDITABLE =
      Set.of("version", "requireVirusScan", "allowedMimeTypes", "maxSizeBytes");

  static UpdateFileCategoryCommand toCommand(Map<String, Object> body) {
    if (body == null
        || !(body.get("version") instanceof Integer || body.get("version") instanceof Long)) {
      throw new FileValidationException("version là bắt buộc (số nguyên)");
    }
    for (String key : body.keySet()) {
      if (!EDITABLE.contains(key)) {
        throw new FileValidationException(
            "Field '" + key + "' không sửa được qua API này (chỉ " + EDITABLE + ")");
      }
    }
    return new UpdateFileCategoryCommand(
        ((Number) body.get("version")).longValue(),
        optionalBoolean(body, "requireVirusScan"),
        optionalStringList(body, "allowedMimeTypes"),
        optionalLong(body, "maxSizeBytes"),
        null,
        false);
  }

  private static Boolean optionalBoolean(Map<String, Object> body, String field) {
    Object v = body.get(field);
    if (v == null || v instanceof Boolean) {
      return (Boolean) v;
    }
    throw wrongType(field, "boolean");
  }

  private static Long optionalLong(Map<String, Object> body, String field) {
    Object v = body.get(field);
    if (v == null) {
      return null;
    }
    if (v instanceof Integer || v instanceof Long) {
      return ((Number) v).longValue();
    }
    throw wrongType(field, "số nguyên");
  }

  private static List<String> optionalStringList(Map<String, Object> body, String field) {
    Object v = body.get(field);
    if (v == null) {
      return null;
    }
    if (v instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    throw wrongType(field, "mảng string");
  }

  private static FileValidationException wrongType(String field, String expected) {
    return new FileValidationException(field + " phải là " + expected);
  }
}
