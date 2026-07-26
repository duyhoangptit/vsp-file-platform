package com.company.platform.import_.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.company.platform.import_.FileParser;
import com.company.platform.import_.RecordPersister;
import com.company.platform.import_.SchemaValidator;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class UserImportKitFactoryTest {

  private final UserSchemaValidator userSchemaValidator = mock(UserSchemaValidator.class);
  private final AppUserRecordPersister appUserRecordPersister = mock(AppUserRecordPersister.class);
  private final UserImportKitFactory factory =
      new UserImportKitFactory(userSchemaValidator, appUserRecordPersister);

  @Test
  void should_returnInjectedSchemaValidator_when_creatingSchemaValidator() {
    SchemaValidator<UserImportRecord> validator = factory.createSchemaValidator();

    assertThat(validator).isSameAs(userSchemaValidator);
  }

  @Test
  void should_returnInjectedRecordPersister_when_creatingRecordPersister() {
    RecordPersister<UserImportRecord> persister = factory.createRecordPersister();

    assertThat(persister).isSameAs(appUserRecordPersister);
  }

  @Test
  void should_returnWorkingCsvParser_when_creatingFileParser() throws Exception {
    String csv = "email,full_name,status,role\na@b.com,Alice,ACTIVE,MEMBER\n";

    try (FileParser<UserImportRecord> parser =
        factory.createFileParser(new ByteArrayInputStream(csv.getBytes()))) {
      assertThat(parser).isInstanceOf(CsvUserFileParser.class);
      assertThat(parser.header()).containsExactly("email", "full_name", "status", "role");
    }
  }
}
