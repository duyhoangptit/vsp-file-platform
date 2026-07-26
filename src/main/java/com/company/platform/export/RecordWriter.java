package com.company.platform.export;

import java.io.IOException;

public interface RecordWriter<T> extends AutoCloseable {

  void writeRecord(T record) throws IOException;

  @Override
  void close() throws IOException;
}
