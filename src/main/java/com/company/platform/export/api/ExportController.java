package com.company.platform.export.api;

import com.company.platform.export.application.ExportJobService;
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
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/exports")
public class ExportController {

  private final ExportJobService exportJobService;
  private final UserContext userContext;

  public ExportController(ExportJobService exportJobService, UserContext userContext) {
    this.exportJobService = exportJobService;
    this.userContext = userContext;
  }

  @Operation(
      summary = "Tạo job export CSV danh sách user",
      description =
          "Tạo job nền EXPORT_USER_CSV chạy bất đồng bộ — không trả file ngay, poll trạng thái"
              + " qua GET /exports/{id}. Nếu user đã có đủ app.job.max-active-per-user job"
              + " PENDING/PROCESSING, hoặc vượt rate limit tạo job loại này, request bị từ chối.")
  @ApiResponse(
      responseCode = "200",
      description = "Job đã tạo (hoặc job cũ nếu trùng idempotencyKey)")
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content =
          @Content(
              schema = @Schema(implementation = AuthExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":401,\"message\":\"Thiếu header X-User-Id\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @ApiResponse(
      responseCode = "409",
      description = "User đã có đủ số job PENDING/PROCESSING tối đa (app.job.max-active-per-user)",
      content =
          @Content(
              schema = @Schema(implementation = ExportApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":409,\"message\":\"User 11111111-1111-1111-1111-111111111111"
                              + " đã có đủ 5 job đang PENDING/PROCESSING, không thể tạo"
                              + " thêm\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @ApiResponse(
      responseCode = "429",
      description = "Vượt rate limit tạo job loại EXPORT_USER_CSV",
      content =
          @Content(
              schema = @Schema(implementation = ExportApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":429,\"message\":\"User 11111111-1111-1111-1111-111111111111"
                              + " đã vượt rate limit tạo job loại EXPORT_USER_CSV, thử lại"
                              + " sau\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @PostMapping("/users")
  public ExportJobResponse createUserCsvExport(
      @RequestBody(required = false) CreateExportUserCsvRequest request) {
    // Chạm userContext.userId() trước để ép resolve request-scoped proxy ngay tại đây (401 nếu
    // thiếu header) — cùng convention với FileUploadController, không phụ thuộc việc service bên
    // dưới có gọi tới userContext hay không.
    userContext.userId();
    String idempotencyKey = request != null ? request.idempotencyKey() : null;
    Job job = exportJobService.submitUserCsvExport(userContext, idempotencyKey);
    return ExportJobResponse.from(job);
  }

  @Operation(
      summary = "Lấy trạng thái job export (polling)",
      description =
          "Dùng để FE poll tiến độ và dựng progress bar (mục 11 design doc): status PROCESSING +"
              + " processedUnits/totalUnits để tính %, DONE thì lấy resultLocation, FAILED thì"
              + " hiển thị errorMessage kèm nút thử lại. Khuyến nghị tăng dần chu kỳ poll"
              + " 1s→2s→3s→5s, cap 10s.")
  @ApiResponse(responseCode = "200", description = "Trạng thái job hiện tại")
  @ApiResponse(
      responseCode = "401",
      description = "Thiếu/sai header xác thực tin cậy từ gateway",
      content = @Content(schema = @Schema(implementation = AuthExceptionHandler.ApiError.class)))
  @ApiResponse(
      responseCode = "403",
      description = "Job không thuộc về user hiện tại và user không có role ADMIN",
      content =
          @Content(
              schema = @Schema(implementation = AuthExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":403,\"message\":\"Không có quyền truy cập JOB"
                              + " 22222222-2222-2222-2222-222222222222\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @ApiResponse(
      responseCode = "404",
      description = "Không tìm thấy job",
      content =
          @Content(
              schema = @Schema(implementation = ExportApiExceptionHandler.ApiError.class),
              examples =
                  @ExampleObject(
                      value =
                          "{\"status\":404,\"message\":\"Không tìm thấy job:"
                              + " 22222222-2222-2222-2222-222222222222\",\"timestamp\":\"2026-07-26T10:00:00Z\"}")))
  @GetMapping("/{id}")
  @RequiresAccess(type = ResourceType.JOB, action = Action.READ)
  public ExportJobResponse getExportJob(@PathVariable UUID id) {
    return ExportJobResponse.from(exportJobService.getJob(id));
  }
}
