package com.company.platform.import_.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.import_.SchemaValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserSchemaValidatorTest {

  private final UserSchemaValidator validator = new UserSchemaValidator();

  @Test
  void should_notThrow_when_headerHasAllRequiredColumns() {
    assertThatCode(
            () ->
                validator.validateHeader(
                    List.of("email", "full_name", "status", "role", "phone_number")))
        .doesNotThrowAnyException();
  }

  @Test
  void should_throw_when_headerMissingRequiredColumn() {
    assertThatThrownBy(() -> validator.validateHeader(List.of("email", "full_name", "role")))
        .isInstanceOf(SchemaValidationException.class)
        .hasMessageContaining("status");
  }

  @Test
  void should_notThrow_when_recordHasAllRequiredFieldsAndValidLengths() {
    UserImportRecord record =
        new UserImportRecord("a@b.com", "Alice", "0900000001", "ACTIVE", "MEMBER", "VN", "Hanoi");

    assertThatCode(() -> validator.validate(record, 1)).doesNotThrowAnyException();
  }

  @Test
  void should_throw_when_requiredFieldIsBlank() {
    UserImportRecord record =
        new UserImportRecord("", "Alice", null, "ACTIVE", "MEMBER", null, null);

    assertThatThrownBy(() -> validator.validate(record, 3))
        .isInstanceOf(SchemaValidationException.class)
        .hasMessageContaining("Dòng 3")
        .hasMessageContaining("email");
  }

  @Test
  void should_throw_when_fieldExceedsColumnMaxLength() {
    String tooLong = "x".repeat(21);
    UserImportRecord record =
        new UserImportRecord("a@b.com", "Alice", null, "ACTIVE", tooLong, null, null);

    assertThatThrownBy(() -> validator.validate(record, 5))
        .isInstanceOf(SchemaValidationException.class)
        .hasMessageContaining("role");
  }
}
