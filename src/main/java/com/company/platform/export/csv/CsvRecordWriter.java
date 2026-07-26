package com.company.platform.export.csv;

import com.company.platform.export.RecordWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

class CsvRecordWriter implements RecordWriter<AppUser> {

  private static final String[] HEADER = {
    "id",
    "email",
    "full_name",
    "phone_number",
    "status",
    "role",
    "country",
    "city",
    "created_at",
    "updated_at"
  };

  private final CSVPrinter printer;

  CsvRecordWriter(OutputStream destination) throws IOException {
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader(HEADER).build();
    this.printer =
        new CSVPrinter(new OutputStreamWriter(destination, StandardCharsets.UTF_8), format);
  }

  @Override
  public void writeRecord(AppUser record) throws IOException {
    printer.printRecord(
        record.getId(),
        record.getEmail(),
        record.getFullName(),
        record.getPhoneNumber(),
        record.getStatus(),
        record.getRole(),
        record.getCountry(),
        record.getCity(),
        record.getCreatedAt(),
        record.getUpdatedAt());
  }

  @Override
  public void close() throws IOException {
    printer.close(true);
  }
}
