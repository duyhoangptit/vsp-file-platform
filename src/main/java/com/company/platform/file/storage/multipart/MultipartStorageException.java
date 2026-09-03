package com.company.platform.file.storage.multipart;

/** Lỗi khi gọi storage multipart (AWS SDK v2 → MinIO). Bọc S3Exception thô lại. */
public class MultipartStorageException extends RuntimeException {
  public MultipartStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
