package com.company.platform.file.api;

import com.company.platform.file.application.FileDownloadService;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.AuthExceptionHandler;
import com.company.platform.shared.auth.RequiresAccess;
import com.company.platform.shared.auth.ResourceType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files")
public class FileDownloadController {

  private final FileDownloadService fileDownloadService;

  public FileDownloadController(FileDownloadService fileDownloadService) {
    this.fileDownloadService = fileDownloadService;
  }

  @Operation(
      summary = "Sinh presigned download URL",
      description =
          "Chỉ sinh được khi file_assets.storage_status = CLEAN (mục 5/8 design doc) — bất biến"
              + " áp dụng ngay cả khi chưa bật virus scan thật, để không phải sửa lại logic khi bật"
              + " scan sau này. Authorization check trước khi sinh URL, kể cả DENIED đều ghi audit"
              + " log.")
  @ApiResponse(responseCode = "200", description = "Đã sinh presigned download URL")
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
      description = "File chưa ở trạng thái CLEAN (VD đang UPLOADING/SCANNING), chưa thể tải xuống",
      content =
          @Content(
              schema = @Schema(implementation = FileApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":409,\"message\":\"File asset"
                              + " 33333333-3333-3333-3333-333333333333 chưa sẵn sàng để tải xuống"
                              + " (trạng thái hiện tại: UPLOADED_DMZ)\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @GetMapping("/{fileAssetId}/download-url")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.READ)
  public FileDownloadResponse getDownloadUrl(@PathVariable UUID fileAssetId) {
    return FileDownloadResponse.from(fileDownloadService.generateDownloadUrl(fileAssetId));
  }
}
