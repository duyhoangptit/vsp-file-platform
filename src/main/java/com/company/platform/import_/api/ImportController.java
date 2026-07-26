package com.company.platform.import_.api;

import com.company.platform.import_.application.ImportJobService;
import com.company.platform.job.domain.Job;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/imports")
public class ImportController {

  private final ImportJobService importJobService;
  private final UserContext userContext;

  public ImportController(ImportJobService importJobService, UserContext userContext) {
    this.importJobService = importJobService;
    this.userContext = userContext;
  }

  @Operation(
      summary = "Tạo job import CSV danh sách user",
      description =
          "Tạo job nền IMPORT_USER chạy bất đồng bộ từ file CSV đã upload+confirm (category"
              + " IMPORT_RAW, storageStatus CLEAN) — poll trạng thái qua GET /imports/{id}. Nếu"
              + " user đã có đủ app.job.max-active-per-user job PENDING/PROCESSING, hoặc vượt"
              + " rate limit tạo job loại này, request bị từ chối.")
  @ApiResponse(
      responseCode = "200",
      description = "Job đã tạo (hoặc job cũ nếu trùng idempotencyKey)")
  @ApiResponse(
      responseCode = "400",
      description = "fileAssetId thiếu hoặc không hợp lệ (Bean Validation @NotNull thất bại)")
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "409",
      description = "User đã có đủ số job PENDING/PROCESSING tối đa (app.job.max-active-per-user)",
      content =
          @Content(
              schema = @Schema(implementation = ImportApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":409,\"message\":\"User 11111111-1111-1111-1111-111111111111"
                              + " đã có đủ 5 job đang PENDING/PROCESSING, không thể tạo"
                              + " thêm\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @ApiResponse(
      responseCode = "429",
      description = "Vượt rate limit tạo job loại IMPORT_USER",
      content =
          @Content(
              schema = @Schema(implementation = ImportApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":429,\"message\":\"User 11111111-1111-1111-1111-111111111111"
                              + " đã vượt rate limit tạo job loại IMPORT_USER, thử lại"
                              + " sau\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @PostMapping("/users")
  public ImportJobResponse createUserImport(@Valid @RequestBody CreateImportUserRequest request) {
    Job job =
        importJobService.submitUserImport(
            userContext, request.fileAssetId(), request.idempotencyKey());
    return ImportJobResponse.from(job);
  }

  @Operation(
      summary = "Lấy trạng thái job import (polling)",
      description =
          "Dùng để FE poll tiến độ và dựng progress bar (mục 11 design doc): status PROCESSING +"
              + " processedUnits/totalUnits để tính %, DONE thì kiểm tra resultLocation (báo cáo"
              + " lỗi nếu có dòng import thất bại), FAILED thì hiển thị errorMessage kèm nút thử"
              + " lại. Khuyến nghị tăng dần chu kỳ poll 1s→2s→3s→5s, cap 10s.")
  @ApiResponse(responseCode = "200", description = "Trạng thái job hiện tại")
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "403",
      description = "Job không thuộc về user hiện tại và user không có role ADMIN",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Không tìm thấy job",
      content =
          @Content(
              schema = @Schema(implementation = ImportApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":404,\"message\":\"Không tìm thấy job:"
                              + " 22222222-2222-2222-2222-222222222222\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @GetMapping("/{id}")
  @RequiresAccess(type = ResourceType.JOB, action = Action.READ)
  public ImportJobResponse getImportJob(@PathVariable UUID id) {
    return ImportJobResponse.from(importJobService.getJob(id));
  }
}
