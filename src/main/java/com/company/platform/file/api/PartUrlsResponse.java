package com.company.platform.file.api;

import com.company.platform.file.application.PartUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record PartUrlsResponse(
    @Schema(description = "Presigned PUT URL cho từng part đã yêu cầu, theo đúng thứ tự request.")
        List<PartUrlView> urls) {

  public record PartUrlView(
      @Schema(description = "Số part (1-based).", example = "1") int partNumber,
      @Schema(
              description =
                  "Presigned PUT URL — client PUT nội dung part thẳng lên MinIO bằng URL này, lấy"
                      + " ETag từ response header để dùng ở bước complete.",
              example = "https://minio:9000/import-raw/9f8c1e2a-...?partNumber=1&uploadId=...")
          String url,
      @Schema(description = "Thời điểm presigned URL hết hạn (30 phút theo thiết kế).")
          Instant expiresAt) {}

  public static PartUrlsResponse from(List<PartUrl> urls) {
    return new PartUrlsResponse(
        urls.stream().map(u -> new PartUrlView(u.partNumber(), u.url(), u.expiresAt())).toList());
  }
}
