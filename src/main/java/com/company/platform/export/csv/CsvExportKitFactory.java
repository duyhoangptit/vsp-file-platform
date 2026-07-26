package com.company.platform.export.csv;

import com.company.platform.export.DataReader;
import com.company.platform.export.ExportKitFactory;
import com.company.platform.export.RecordWriter;
import com.company.platform.export.ResultUploader;
import java.io.IOException;
import java.io.OutputStream;
import org.springframework.stereotype.Component;

@Component
public class CsvExportKitFactory implements ExportKitFactory<AppUser> {

  private final AppUserDataReader appUserDataReader;
  private final ResultUploader resultUploader;

  public CsvExportKitFactory(AppUserDataReader appUserDataReader, ResultUploader resultUploader) {
    this.appUserDataReader = appUserDataReader;
    this.resultUploader = resultUploader;
  }

  @Override
  public DataReader<AppUser> createDataReader() {
    return appUserDataReader;
  }

  @Override
  public RecordWriter<AppUser> createRecordWriter(OutputStream destination) throws IOException {
    return new CsvRecordWriter(destination);
  }

  @Override
  public ResultUploader createResultUploader() {
    return resultUploader;
  }
}
