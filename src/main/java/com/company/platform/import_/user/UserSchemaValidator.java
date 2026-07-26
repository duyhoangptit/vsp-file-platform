package com.company.platform.import_.user;

import com.company.platform.import_.SchemaValidationException;
import com.company.platform.import_.SchemaValidator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Cột bắt buộc khớp NOT NULL constraint của bảng app_users (005-init-app-users.yaml); giới hạn độ
 * dài khớp VARCHAR size của từng cột, tránh để insert thất bại giữa chừng batch vì lỗi DB.
 */
@Component
class UserSchemaValidator implements SchemaValidator<UserImportRecord> {

  private static final List<String> REQUIRED_COLUMNS =
      List.of("email", "full_name", "status", "role");

  @Override
  public void validateHeader(List<String> header) {
    for (String required : REQUIRED_COLUMNS) {
      if (!header.contains(required)) {
        throw new SchemaValidationException("Thiếu cột bắt buộc: " + required);
      }
    }
  }

  @Override
  public void validate(UserImportRecord record, long rowNumber) {
    requireNonBlank(record.email(), "email", rowNumber);
    requireNonBlank(record.fullName(), "full_name", rowNumber);
    requireNonBlank(record.status(), "status", rowNumber);
    requireNonBlank(record.role(), "role", rowNumber);
    checkMaxLength(record.email(), 255, "email", rowNumber);
    checkMaxLength(record.fullName(), 255, "full_name", rowNumber);
    checkMaxLength(record.status(), 20, "status", rowNumber);
    checkMaxLength(record.role(), 20, "role", rowNumber);
    checkMaxLength(record.phoneNumber(), 50, "phone_number", rowNumber);
    checkMaxLength(record.country(), 100, "country", rowNumber);
    checkMaxLength(record.city(), 100, "city", rowNumber);
  }

  private void requireNonBlank(String value, String columnName, long rowNumber) {
    if (value == null || value.isBlank()) {
      throw new SchemaValidationException(rowNumber, "cột " + columnName + " không được để trống");
    }
  }

  private void checkMaxLength(String value, int maxLength, String columnName, long rowNumber) {
    if (value != null && value.length() > maxLength) {
      throw new SchemaValidationException(
          rowNumber, "cột %s vượt quá %d ký tự".formatted(columnName, maxLength));
    }
  }
}
