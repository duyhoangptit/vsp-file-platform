package com.company.platform.export.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CsvRecordWriterTest {

  private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void should_writeHeaderAndRows_when_writingRecords() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    UUID id = UUID.randomUUID();
    AppUser user =
        new AppUser(
            id,
            "a@b.com",
            "Alice",
            "0900000000",
            "ACTIVE",
            "MEMBER",
            "VN",
            "Hanoi",
            TIMESTAMP,
            TIMESTAMP);

    try (CsvRecordWriter writer = new CsvRecordWriter(out)) {
      writer.writeRecord(user);
    }

    String csv = out.toString(StandardCharsets.UTF_8);
    String[] lines = csv.split("\r\n");
    assertThat(lines[0])
        .isEqualTo(
            "id,email,full_name,phone_number,status,role,country,city,created_at,updated_at");
    assertThat(lines[1]).startsWith(id + ",a@b.com,Alice,0900000000,ACTIVE,MEMBER,VN,Hanoi");
  }

  @Test
  void should_quoteField_when_valueContainsCommaOrQuote() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AppUser user =
        new AppUser(
            UUID.randomUUID(),
            "a@b.com",
            "Alice, \"The Great\"",
            null,
            "ACTIVE",
            "MEMBER",
            "VN",
            "Hanoi",
            TIMESTAMP,
            TIMESTAMP);

    try (CsvRecordWriter writer = new CsvRecordWriter(out)) {
      writer.writeRecord(user);
    }

    String csv = out.toString(StandardCharsets.UTF_8);
    assertThat(csv).contains("\"Alice, \"\"The Great\"\"\"");
  }
}
