package com.company.platform.file.api;

import com.company.platform.file.application.FileUploadInitiation;
import com.company.platform.file.application.FileUploadService;
import com.company.platform.file.application.InitiateFileUploadCommand;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {

  private final FileUploadService fileUploadService;
  private final UserContext userContext;

  public FileUploadController(FileUploadService fileUploadService, UserContext userContext) {
    this.fileUploadService = fileUploadService;
    this.userContext = userContext;
  }

  @Operation(
      summary = "Khởi tạo upload file, sinh presigned PUT URL",
      description =
          "Bước 1/2 của flow upload (mục 8 design doc): server tạo file_assets record"
              + " (storageStatus = UPLOADING) và trả presigned PUT URL — client dùng URL này upload"
              + " thẳng lên MinIO (bypass backend), sau đó gọi"
              + " POST /api/files/{fileAssetId}/confirm để hoàn tất.")
  @ApiResponse(responseCode = "200", description = "Đã tạo file asset + presigned upload URL")
  @ApiResponse(
      responseCode = "400",
      description =
          "Bean Validation thất bại, hoặc mimeType/sizeBytes vượt giới hạn cho phép của category",
      content = @Content(schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "404",
      description = "categoryCode không tồn tại trong file_categories",
      content =
          @Content(
              schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":404,\"message\":\"Không tìm thấy file category:"
                              + " IMPORT_RAW\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @PostMapping
  public InitiateFileUploadResponse initiateUpload(
      @Valid @RequestBody InitiateFileUploadRequest request) {
    FileUploadInitiation initiation =
        fileUploadService.initiateUpload(
            new InitiateFileUploadCommand(
                request.categoryCode(),
                request.originalFilename(),
                request.mimeType(),
                request.sizeBytes(),
                request.ownerType(),
                userContext.userId(),
                userContext.userId()));
    return InitiateFileUploadResponse.from(initiation);
  }

  @Operation(
      summary = "Xác nhận đã upload xong lên MinIO",
      description =
          "Bước 2/2 của flow upload (mục 8 design doc): client tự tính SHA-256 sau khi PUT xong"
              + " rồi gọi endpoint này — server verify tồn tại + kích thước qua MinIO HEAD object,"
              + " lưu checksum, chuyển storageStatus UPLOADED_DMZ -> CLEAN (chưa bật virus scan).")
  @ApiResponse(responseCode = "200", description = "File asset đã ở trạng thái CLEAN")
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
      content =
          @Content(
              schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":404,\"message\":\"Không tìm thấy file asset:"
                              + " 33333333-3333-3333-3333-333333333333\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @ApiResponse(
      responseCode = "409",
      description = "File asset đang ở trạng thái không hợp lệ để confirm (VD đã CLEAN từ trước)",
      content =
          @Content(
              schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":409,\"message\":\"File asset"
                              + " 33333333-3333-3333-3333-333333333333 đang ở trạng thái CLEAN,"
                              + " không thể confirm upload\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @ApiResponse(
      responseCode = "422",
      description = "Verify thất bại — object không tồn tại trên MinIO hoặc kích thước không khớp",
      content =
          @Content(
              schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":422,\"message\":\"Xác thực file asset"
                              + " 33333333-3333-3333-3333-333333333333 thất bại: object không tồn"
                              + " tại trên MinIO\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @PostMapping("/{fileAssetId}/confirm")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public FileAssetResponse confirmUpload(
      @PathVariable UUID fileAssetId, @Valid @RequestBody ConfirmFileUploadRequest request) {
    FileAsset fileAsset = fileUploadService.confirmUpload(fileAssetId, request.checksumSha256());
    return FileAssetResponse.from(fileAsset);
  }
}
