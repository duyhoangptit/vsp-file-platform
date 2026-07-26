package com.company.platform.import_;

import java.io.IOException;
import java.util.List;

public interface FileParser<T> extends AutoCloseable {

  List<String> header();

  /** Trả null khi đã đọc hết file. */
  T readNextRecord() throws IOException;

  @Override
  void close() throws IOException;
}
