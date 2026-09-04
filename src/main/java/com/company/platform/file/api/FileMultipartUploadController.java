package com.company.platform.file.api;

import com.company.platform.file.application.InitiateMultipartUploadCommand;
import com.company.platform.file.application.MultipartUploadService;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.AuthExceptionHandler;
import com.company.platform.shared.auth.RequiresAccess;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow multipart upload cho file lớn (mục 8.1 design doc): initiate -> xin presigned part URL theo
 * lô -> client PUT từng part thẳng lên MinIO -> complete (server gộp + verify size + chuyển CLEAN)
 * hoặc abort. Authorization check ở tầng API qua {@link RequiresAccess}; worker/service tin request
 * đã được authorize.
 */
@RestController
@RequestMapping("/api/files/multipart")
public class FileMultipartUploadController {

  private final MultipartUploadService multipartUploadService;
  private final UserContext userContext;

  public FileMultipartUploadController(
      MultipartUploadService multipartUploadService, UserContext userContext) {
    this.multipartUploadService = multipartUploadService;
    this.userContext = userContext;
  }

  @Operation(
      summary = "Khởi tạo multipart upload cho file lớn",
      description =
          "Bước 1 của flow multipart (mục 8.1 design doc): server tạo file_assets record"
              + " (storageStatus = UPLOADING), mở multipart upload trên MinIO, gắn multipartUploadId"
              + " và trả về partSizeBytes + maxParts để client tự chia file. Không có @RequiresAccess"
              + " vì chưa có resource — mirror POST /api/files.")
  @ApiResponse(responseCode = "200", description = "Đã tạo file asset + mở multipart upload")
  @ApiResponse(
      responseCode = "400",
      description = "Bean Validation thất bại, hoặc mimeType không nằm trong allowed_mime_types",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "404",
      description = "categoryCode không tồn tại trong file_categories",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Category không bật multipart (thiếu multipart_part_size_bytes)",
      content =
          @Content(
              schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":409,\"message\":\"File category AVATAR không bật multipart"
                              + " upload (thiếu multipart_part_size_bytes)\",\"timestamp\":\"2026-09-03T10:00:00Z\"}")))
  @PostMapping
  public InitiateMultipartUploadResponse initiate(
      @Valid @RequestBody InitiateMultipartUploadRequest request) {
    return InitiateMultipartUploadResponse.from(
        multipartUploadService.initiateMultipart(
            new InitiateMultipartUploadCommand(
                request.categoryCode(),
                request.originalFilename(),
                request.mimeType(),
                request.ownerType(),
                userContext.userId(),
                userContext.userId())));
  }

  @Operation(
      summary = "Sinh presigned PUT URL cho các part",
      description =
          "Bước 2 của flow multipart: client xin URL theo lô các số part, PUT nội dung từng part"
              + " thẳng lên MinIO bằng URL trả về, giữ lại ETag cho bước complete. Có thể gọi lại để"
              + " lấy URL mới khi retry 1 part.")
  @ApiResponse(
      responseCode = "200",
      description = "Danh sách presigned PUT URL theo thứ tự request")
  @ApiResponse(
      responseCode = "400",
      description = "partNumbers rỗng, hoặc có part number ngoài khoảng 1..maxParts",
      content =
          @Content(
              schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":400,\"message\":\"Part number 99 không hợp lệ (phải trong"
                              + " 1..8)\",\"timestamp\":\"2026-09-03T10:00:00Z\"}")))
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "403",
      description = "File asset không thuộc về user hiện tại và user không có role ADMIN",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Không tìm thấy file asset",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "409",
      description = "File asset không phải multipart upload đang mở",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @PostMapping("/{fileAssetId}/part-urls")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public PartUrlsResponse partUrls(
      @PathVariable UUID fileAssetId, @Valid @RequestBody PartUrlsRequest request) {
    return PartUrlsResponse.from(
        multipartUploadService.generatePartUrls(fileAssetId, request.partNumbers()));
  }

  @Operation(
      summary = "Liệt kê các part đã upload xong",
      description =
          "Client dùng để đối chiếu part nào đã lên MinIO (nguồn sự thật là S3 ListParts) trước khi"
              + " complete hoặc để resume upload dở dang.")
  @ApiResponse(
      responseCode = "200",
      description = "Danh sách part đã upload (số part + ETag + size)")
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "403",
      description = "File asset không thuộc về user hiện tại và user không có role ADMIN",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Không tìm thấy file asset",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "409",
      description = "File asset không phải multipart upload đang mở",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @GetMapping("/{fileAssetId}/parts")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public UploadedPartsResponse parts(@PathVariable UUID fileAssetId) {
    return UploadedPartsResponse.from(multipartUploadService.listUploadedParts(fileAssetId));
  }

  @Operation(
      summary = "Hoàn tất multipart upload",
      description =
          "Bước cuối: client gửi danh sách (partNumber, ETag) + checksum SHA-256. Server verify tổng"
              + " size <= max_size_bytes TRƯỚC khi complete, gộp part trên MinIO, lưu checksum,"
              + " chuyển storageStatus -> CLEAN. Idempotent nếu file asset đã CLEAN.")
  @ApiResponse(responseCode = "200", description = "File asset đã ở trạng thái CLEAN")
  @ApiResponse(
      responseCode = "400",
      description = "Bean Validation thất bại (parts rỗng, partNumber <= 0, etag rỗng)",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "403",
      description = "File asset không thuộc về user hiện tại và user không có role ADMIN",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Không tìm thấy file asset",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "409",
      description = "File asset không có multipart upload đang mở để complete",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "422",
      description = "Tổng kích thước các part vượt trần max_size_bytes của category",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @PostMapping("/{fileAssetId}/complete")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public FileAssetResponse complete(
      @PathVariable UUID fileAssetId, @Valid @RequestBody CompleteMultipartUploadRequest request) {
    FileAsset fileAsset = multipartUploadService.completeMultipart(request.toCommand(fileAssetId));
    return FileAssetResponse.from(fileAsset);
  }

  @Operation(
      summary = "Huỷ multipart upload",
      description =
          "Abort multipart upload trên MinIO (giải phóng part đã upload) và soft-delete file asset."
              + " Dùng khi client bỏ dở hoặc lỗi không phục hồi được.")
  @ApiResponse(responseCode = "204", description = "Đã abort + soft-delete file asset")
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "403",
      description = "File asset không thuộc về user hiện tại và user không có role ADMIN",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Không tìm thấy file asset",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @PostMapping("/{fileAssetId}/abort")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public void abort(@PathVariable UUID fileAssetId) {
    multipartUploadService.abortMultipart(fileAssetId);
  }
}
