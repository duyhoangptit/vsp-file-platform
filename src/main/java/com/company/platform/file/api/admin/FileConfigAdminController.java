package com.company.platform.file.api.admin;

import com.company.platform.file.application.admin.FileCategoryAdminService;
import com.company.platform.file.application.admin.VirusScanSettingsService;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.shared.security.AdminActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@Tag(
    name = "Admin — cấu hình file",
    description = "Virus scan (tổng + theo category), file category")
@SecurityRequirement(name = "bearer-jwt")
public class FileConfigAdminController {

  private final VirusScanSettingsService virusScanSettingsService;
  private final FileCategoryAdminService fileCategoryAdminService;
  private final AdminActor adminActor;

  public FileConfigAdminController(
      VirusScanSettingsService virusScanSettingsService,
      FileCategoryAdminService fileCategoryAdminService,
      AdminActor adminActor) {
    this.virusScanSettingsService = virusScanSettingsService;
    this.fileCategoryAdminService = fileCategoryAdminService;
    this.adminActor = adminActor;
  }

  @Operation(summary = "Xem cấu hình virus scan", description = "Quyền file.config.view.")
  @GetMapping("/virus-scan")
  public VirusScanResponse getVirusScan() {
    return VirusScanResponse.from(virusScanSettingsService.overview());
  }

  @Operation(
      summary = "Bật/tắt công tắc tổng virus scan",
      description =
          "Quyền file.config.manage. Gửi kèm version đang thấy; version lệch → 409. Chỉ là cấu"
              + " hình: chưa có engine scan thật (engineIntegrated=false).")
  @ApiResponse(responseCode = "409", description = "version đã cũ — tải lại rồi thử lại")
  @PutMapping("/virus-scan")
  public VirusScanResponse updateVirusScan(
      @Valid @org.springframework.web.bind.annotation.RequestBody UpdateVirusScanRequest request) {
    return VirusScanResponse.from(
        virusScanSettingsService.updateGlobal(
            request.enabled(), request.version(), adminActor.currentId()));
  }

  @Operation(summary = "Danh sách file category", description = "Quyền file.config.view.")
  @GetMapping("/file-categories")
  public List<FileCategoryAdminResponse> listCategories() {
    return fileCategoryAdminService.list().stream().map(this::toResponse).toList();
  }

  @Operation(summary = "Chi tiết file category", description = "Quyền file.config.view.")
  @GetMapping("/file-categories/{code}")
  public FileCategoryAdminResponse getCategory(@PathVariable String code) {
    return toResponse(fileCategoryAdminService.get(code));
  }

  @Operation(
      summary = "Sửa file category",
      description =
          "Quyền file.config.manage. version bắt buộc; field vắng mặt = không đổi;"
              + " \"retentionDays\": null = xoá retention. Không sửa được code, bucketName,"
              + " publicAccess, multipartPartSizeBytes. Mime type dạng type/subtype, không wildcard."
              + " Chỉ áp cho upload mới.")
  @RequestBody(
      content =
          @Content(
              examples =
                  @ExampleObject(
                      value =
                          "{\"version\":0,\"requireVirusScan\":true,"
                              + "\"allowedMimeTypes\":[\"image/png\",\"image/jpeg\"],"
                              + "\"maxSizeBytes\":5242880,\"retentionDays\":null}")))
  @ApiResponse(responseCode = "400", description = "Body không hợp lệ")
  @ApiResponse(responseCode = "404", description = "Không có category")
  @ApiResponse(responseCode = "409", description = "version đã cũ")
  @PatchMapping("/file-categories/{code}")
  public FileCategoryAdminResponse updateCategory(
      @PathVariable String code,
      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
    FileCategory updated =
        fileCategoryAdminService.update(
            code, UpdateFileCategoryRequest.toCommand(body), adminActor.currentId());
    return toResponse(updated);
  }

  private FileCategoryAdminResponse toResponse(FileCategory category) {
    return FileCategoryAdminResponse.from(
        category, fileCategoryAdminService.effectiveVirusScan(category));
  }
}
