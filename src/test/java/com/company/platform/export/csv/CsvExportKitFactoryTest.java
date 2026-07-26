package com.company.platform.export.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.company.platform.export.DataReader;
import com.company.platform.export.RecordWriter;
import com.company.platform.export.ResultUploader;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class CsvExportKitFactoryTest {

  private final AppUserDataReader appUserDataReader = mock(AppUserDataReader.class);
  private final ResultUploader resultUploader = mock(ResultUploader.class);
  private final CsvExportKitFactory factory =
      new CsvExportKitFactory(appUserDataReader, resultUploader);

  @Test
  void should_returnInjectedDataReader_when_creatingDataReader() {
    DataReader<AppUser> dataReader = factory.createDataReader();

    assertThat(dataReader).isSameAs(appUserDataReader);
  }

  @Test
  void should_returnInjectedResultUploader_when_creatingResultUploader() {
    ResultUploader created = factory.createResultUploader();

    assertThat(created).isSameAs(resultUploader);
  }

  @Test
  void should_returnWorkingCsvWriter_when_creatingRecordWriter() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try (RecordWriter<AppUser> writer = factory.createRecordWriter(out)) {
      assertThat(writer).isInstanceOf(CsvRecordWriter.class);
    }

    assertThat(out.toString()).contains("id,email,full_name");
  }
}
