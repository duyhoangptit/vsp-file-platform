package com.company.platform.file.api;

import com.company.platform.file.storage.multipart.MultipartStorageService.UploadedPart;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record UploadedPartsResponse(
    @Schema(
            description =
                "Các part đã upload xong lên MinIO (nguồn sự thật lấy từ S3 ListParts) — client"
                    + " dùng để biết còn part nào phải upload/retry trước khi complete.")
        List<UploadedPartView> parts) {

  public record UploadedPartView(
      @Schema(description = "Số part (1-based).", example = "1") int partNumber,
      @Schema(description = "ETag do MinIO trả về khi PUT part.", example = "\"d41d8cd98f00b204\"")
          String etag,
      @Schema(description = "Kích thước part (bytes).", example = "67108864") long sizeBytes) {}

  public static UploadedPartsResponse from(List<UploadedPart> parts) {
    return new UploadedPartsResponse(
        parts.stream()
            .map(p -> new UploadedPartView(p.partNumber(), p.etag(), p.sizeBytes()))
            .toList());
  }
}
