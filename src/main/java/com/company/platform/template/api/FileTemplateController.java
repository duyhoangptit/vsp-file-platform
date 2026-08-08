package com.company.platform.template.api;

import com.company.platform.file.application.FileDownloadResult;
import com.company.platform.shared.auth.UserContext;
import com.company.platform.template.application.ConfirmTemplateUploadCommand;
import com.company.platform.template.application.FileTemplateService;
import com.company.platform.template.application.InitiateTemplateUploadCommand;
import com.company.platform.template.application.TemplateUploadInitiation;
import com.company.platform.template.domain.FileTemplate;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/templates")
public class FileTemplateController {

  private final FileTemplateService fileTemplateService;
  private final UserContext userContext;

  public FileTemplateController(FileTemplateService fileTemplateService, UserContext userContext) {
    this.fileTemplateService = fileTemplateService;
    this.userContext = userContext;
  }

  @Operation(
      summary = "Khởi tạo upload file template, sinh presigned PUT URL",
      description =
          "Bước 1/2 (chỉ ADMIN). Chưa tạo bản ghi version — chỉ tạo sau khi confirm upload thành"
              + " công, tránh version rác nếu client bỏ dở upload.")
  @PostMapping("/upload/initiate")
  public TemplateUploadInitiationResponse initiateUpload(
      @Valid @RequestBody InitiateTemplateUploadRequest request) {
    requireAuthenticated();
    TemplateUploadInitiation initiation =
        fileTemplateService.initiateUpload(
            userContext,
            new InitiateTemplateUploadCommand(
                request.category(),
                request.templateCode(),
                request.originalFilename(),
                request.mimeType(),
                request.sizeBytes()));
    return TemplateUploadInitiationResponse.from(initiation);
  }

  @Operation(
      summary = "Xác nhận upload xong, tạo version mới",
      description =
          "Bước 2/2 (chỉ ADMIN). Version tự tăng theo (category, templateCode), tính trong cùng"
              + " transaction với advisory lock để tránh đụng version khi 2 request confirm đồng"
              + " thời cho cùng cặp category/templateCode.")
  @PostMapping("/upload/confirm")
  public FileTemplateResponse confirmUpload(
      @Valid @RequestBody ConfirmTemplateUploadRequest request) {
    requireAuthenticated();
    FileTemplate fileTemplate =
        fileTemplateService.confirmUpload(
            userContext,
            new ConfirmTemplateUploadCommand(
                request.fileAssetId(),
                request.category(),
                request.templateCode(),
                request.checksumSha256(),
                request.description()));
    return FileTemplateResponse.from(fileTemplate);
  }

  @Operation(
      summary = "Sinh presigned download URL cho file template",
      description =
          "Không truyền version -> lấy bản mới nhất. Mọi user đã đăng nhập đều tải được (template"
              + " là tài nguyên dùng chung, không theo owner_id).")
  @GetMapping("/download-url")
  public TemplateDownloadUrlResponse getDownloadUrl(
      @RequestParam String category,
      @RequestParam String templateCode,
      @RequestParam(required = false) Integer version) {
    requireAuthenticated();
    FileDownloadResult result =
        fileTemplateService.generateDownloadUrl(userContext, category, templateCode, version);
    return TemplateDownloadUrlResponse.from(result);
  }

  /**
   * FileTemplateService nhận nguyên {@link UserContext} (không tách sẵn userId() như {@code
   * FileUploadController}) vì cần cả roles() để check ADMIN — nên nếu không có lời gọi method nào
   * trên proxy request-scoped này trước khi service xử lý, request thiếu header X-User-Id sẽ không
   * bao giờ lộ ra {@code UnauthenticatedException} trong test dùng {@code @WebMvcTest} (service bị
   * mock, không có code thật nào đụng vào userContext). Gọi userId() ở đây để ép resolve sớm, fail
   * nhanh — hành vi production không đổi vì service thật cũng luôn resolve nó qua roles().
   */
  private void requireAuthenticated() {
    userContext.userId();
  }
}
