package com.company.platform.import_.user;

import com.company.platform.import_.FileParser;
import com.company.platform.import_.ImportKitFactory;
import com.company.platform.import_.RecordPersister;
import com.company.platform.import_.SchemaValidator;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;

@Component
public class UserImportKitFactory implements ImportKitFactory<UserImportRecord> {

  private final UserSchemaValidator userSchemaValidator;
  private final AppUserRecordPersister appUserRecordPersister;

  UserImportKitFactory(
      UserSchemaValidator userSchemaValidator, AppUserRecordPersister appUserRecordPersister) {
    this.userSchemaValidator = userSchemaValidator;
    this.appUserRecordPersister = appUserRecordPersister;
  }

  @Override
  public FileParser<UserImportRecord> createFileParser(InputStream source) throws IOException {
    return new CsvUserFileParser(source);
  }

  @Override
  public SchemaValidator<UserImportRecord> createSchemaValidator() {
    return userSchemaValidator;
  }

  @Override
  public RecordPersister<UserImportRecord> createRecordPersister() {
    return appUserRecordPersister;
  }
}
