package com.company.platform.import_.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvUserFileParserTest {

  @Test
  void should_parseHeaderAndRecords_when_csvIsWellFormed() throws Exception {
    String csv =
        """
        email,full_name,phone_number,status,role,country,city
        a@b.com,Alice,0900000001,ACTIVE,MEMBER,VN,Hanoi
        c@d.com,Charlie,,ACTIVE,ADMIN,US,
        """;

    try (CsvUserFileParser parser =
        new CsvUserFileParser(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))) {
      assertThat(parser.header())
          .containsExactly(
              "email", "full_name", "phone_number", "status", "role", "country", "city");

      UserImportRecord first = parser.readNextRecord();
      assertThat(first.email()).isEqualTo("a@b.com");
      assertThat(first.fullName()).isEqualTo("Alice");
      assertThat(first.phoneNumber()).isEqualTo("0900000001");
      assertThat(first.status()).isEqualTo("ACTIVE");
      assertThat(first.role()).isEqualTo("MEMBER");
      assertThat(first.country()).isEqualTo("VN");
      assertThat(first.city()).isEqualTo("Hanoi");

      UserImportRecord second = parser.readNextRecord();
      assertThat(second.email()).isEqualTo("c@d.com");
      assertThat(second.phoneNumber()).isEmpty();
      assertThat(second.city()).isEmpty();

      assertThat(parser.readNextRecord()).isNull();
    }
  }

  @Test
  void should_returnNullForUnmappedColumns_when_optionalColumnsMissingFromHeader()
      throws Exception {
    String csv =
        """
        email,full_name,status,role
        a@b.com,Alice,ACTIVE,MEMBER
        """;

    try (CsvUserFileParser parser =
        new CsvUserFileParser(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))) {
      UserImportRecord record = parser.readNextRecord();

      assertThat(record.phoneNumber()).isNull();
      assertThat(record.country()).isNull();
      assertThat(record.city()).isNull();
    }
  }
}
