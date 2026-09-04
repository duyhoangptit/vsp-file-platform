package com.company.platform.file.api;

import com.company.platform.file.application.FileBytesResult;
import com.company.platform.file.application.FileDownloadService;
import com.company.platform.shared.auth.AuthExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Song song với {@link FileDownloadController#getDownloadUrl} — trả thẳng nội dung byte của file
 * qua HTTP thay vì presigned URL, dành cho service nội bộ gọi server-to-server (không đại diện 1
 * end-user cụ thể). Bảo vệ bởi {@code InternalApiKeyInterceptor} áp cho toàn bộ prefix {@code
 * /api/internal/**} (đăng ký ở InternalApiWebConfig) — cố tình KHÔNG dùng {@code @RequiresAccess}
 * vì không có UserContext đáng tin cậy ở đường gọi này. Xem design doc
 * 2026-08-08-internal-download-bytes-api-design.md.
 */
@RestController
@RequestMapping("/api/internal/files")
public class FileInternalDownloadController {

  private final FileDownloadService fileDownloadService;

  public FileInternalDownloadController(FileDownloadService fileDownloadService) {
    this.fileDownloadService = fileDownloadService;
  }

  @Operation(
      summary = "Tải trực tiếp nội dung byte của file (internal call)",
      description =
          "Chỉ đọc được khi file_assets.storage_status = CLEAN, giống bất biến của"
              + " /download-url. Yêu cầu header X-Internal-Api-Key hợp lệ — không dùng"
              + " UserContext/ownership check vì caller là service nội bộ, không đại diện 1"
              + " end-user cụ thể.")
  @ApiResponse(responseCode = "200", description = "Trả về nội dung file dạng octet-stream")
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header X-Internal-Api-Key",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Không tìm thấy file asset",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "409",
      description = "File chưa ở trạng thái CLEAN, chưa thể tải xuống",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @GetMapping("/{fileAssetId}/bytes")
  public ResponseEntity<StreamingResponseBody> downloadFileBytes(@PathVariable UUID fileAssetId) {
    FileBytesResult result = fileDownloadService.prepareBytesDownload(fileAssetId);

    StreamingResponseBody body =
        outputStream -> {
          try (InputStream content = result.content()) {
            content.transferTo(outputStream);
          }
        };

    ContentDisposition contentDisposition =
        ContentDisposition.attachment()
            .filename(result.originalFilename(), StandardCharsets.UTF_8)
            .build();

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(result.mimeType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
        .contentLength(result.sizeBytes())
        .body(body);
  }
}
