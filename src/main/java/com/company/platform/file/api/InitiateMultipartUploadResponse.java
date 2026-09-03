package com.company.platform.file.api;

import com.company.platform.file.application.MultipartUploadInitiation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record InitiateMultipartUploadResponse(
    @Schema(
            description =
                "ID file asset vừa tạo (storageStatus = UPLOADING, đã gắn multipartUploadId) —"
                    + " dùng ở các bước part-urls / complete / abort.")
        UUID fileAssetId,
    @Schema(
            description =
                "Kích thước mỗi part (bytes) client phải chia file theo — part cuối được phép nhỏ"
                    + " hơn.",
            example = "67108864")
        long partSizeBytes,
    @Schema(
            description =
                "Số part tối đa cho phép (ceil(maxSizeBytes / partSizeBytes), cap 10000 theo giới"
                    + " hạn S3).",
            example = "8")
        int maxParts) {

  public static InitiateMultipartUploadResponse from(MultipartUploadInitiation initiation) {
    return new InitiateMultipartUploadResponse(
        initiation.fileAssetId(), initiation.partSizeBytes(), initiation.maxParts());
  }
}
