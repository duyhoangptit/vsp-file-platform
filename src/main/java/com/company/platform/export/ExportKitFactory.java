package com.company.platform.export;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Abstract Factory (mục 6.1 design doc): tạo bộ DataReader + RecordWriter + ResultUploader khớp
 * định dạng nhau. Thêm format export mới (Excel...) = thêm 1 implementation mới của interface này,
 * không sửa code cũ.
 */
public interface ExportKitFactory<T> {

  DataReader<T> createDataReader();

  RecordWriter<T> createRecordWriter(OutputStream destination) throws IOException;

  ResultUploader createResultUploader();
}
