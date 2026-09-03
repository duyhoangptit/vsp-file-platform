package com.company.platform.file.application;

import java.util.List;
import java.util.UUID;

/**
 * Tham số client gửi khi hoàn tất multipart upload: danh sách part đã upload (số part + ETag do S3
 * trả về lúc PUT part) và checksum SHA-256 client tự tính.
 */
public record CompleteMultipartUploadCommand(
    UUID fileAssetId, List<PartRef> parts, String checksumSha256) {

  public record PartRef(int partNumber, String etag) {}
}
