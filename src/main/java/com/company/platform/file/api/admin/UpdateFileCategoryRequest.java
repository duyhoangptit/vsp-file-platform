package com.company.platform.file.api.admin;

import com.company.platform.file.application.admin.UpdateFileCategoryCommand;
import com.company.platform.file.domain.FileValidationException;
import java.util.List;
import java.util.Map;

/**
 * Body PATCH đọc dạng {@code Map} để phân biệt field vắng mặt (không đổi) với {@code
 * "retentionDays": null} tường minh (xoá retention). Chỉ chấp nhận {@code version} (bắt buộc),
 * {@code requireVirusScan}, {@code allowedMimeTypes}, {@code maxSizeBytes}, {@code retentionDays}.
 */
final class UpdateFileCategoryRequest {

  private UpdateFileCategoryRequest() {}

  static UpdateFileCategoryCommand toCommand(Map<String, Object> body) {
    if (body == null || !(body.get("version") instanceof Number version)) {
      throw new FileValidationException("version là bắt buộc (số nguyên)");
    }
    return new UpdateFileCategoryCommand(
        version.longValue(),
        optionalBoolean(body, "requireVirusScan"),
        optionalStringList(body, "allowedMimeTypes"),
        optionalLong(body, "maxSizeBytes"),
        optionalInt(body, "retentionDays"),
        body.containsKey("retentionDays"));
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

  private static Integer optionalInt(Map<String, Object> body, String field) {
    Object v = body.get(field);
    if (v == null) {
      return null;
    }
    if (v instanceof Integer i) {
      return i;
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
