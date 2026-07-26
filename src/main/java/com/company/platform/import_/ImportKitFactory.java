package com.company.platform.import_;

import java.io.IOException;
import java.io.InputStream;

/**
 * Abstract Factory (mục 6.1 design doc): tạo bộ FileParser + SchemaValidator + RecordPersister khớp
 * định dạng nhau. Thêm format import mới (Excel...) = thêm 1 implementation mới của interface này,
 * không sửa code cũ.
 */
public interface ImportKitFactory<T> {

  FileParser<T> createFileParser(InputStream source) throws IOException;

  SchemaValidator<T> createSchemaValidator();

  RecordPersister<T> createRecordPersister();
}
