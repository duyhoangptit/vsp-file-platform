package com.company.platform.import_.user;

import com.company.platform.import_.FileParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Đọc CSV streaming qua Iterator<CSVRecord> của Commons CSV, không load toàn bộ file vào bộ nhớ.
 */
class CsvUserFileParser implements FileParser<UserImportRecord> {

  private final CSVParser parser;
  private final Iterator<CSVRecord> iterator;

  CsvUserFileParser(InputStream source) throws IOException {
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
    this.parser = CSVParser.parse(new InputStreamReader(source, StandardCharsets.UTF_8), format);
    this.iterator = parser.iterator();
  }

  @Override
  public List<String> header() {
    return parser.getHeaderNames();
  }

  @Override
  public UserImportRecord readNextRecord() {
    if (!iterator.hasNext()) {
      return null;
    }
    CSVRecord record = iterator.next();
    return new UserImportRecord(
        value(record, "email"),
        value(record, "full_name"),
        value(record, "phone_number"),
        value(record, "status"),
        value(record, "role"),
        value(record, "country"),
        value(record, "city"));
  }

  private String value(CSVRecord record, String columnName) {
    return record.isMapped(columnName) ? record.get(columnName) : null;
  }

  @Override
  public void close() throws IOException {
    parser.close();
  }
}
