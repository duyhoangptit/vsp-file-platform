package com.company.platform.export.api;

import com.company.platform.export.application.ExportJobService;
import com.company.platform.job.domain.Job;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.RequiresAccess;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
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

  @GetMapping("/{id}")
  @RequiresAccess(type = ResourceType.JOB, action = Action.READ)
  public ExportJobResponse getExportJob(@PathVariable UUID id) {
    return ExportJobResponse.from(exportJobService.getJob(id));
  }
}
