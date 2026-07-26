package com.company.platform.import_.api;

import com.company.platform.import_.application.ImportJobService;
import com.company.platform.job.domain.Job;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.RequiresAccess;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
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

  @PostMapping("/users")
  public ImportJobResponse createUserImport(@Valid @RequestBody CreateImportUserRequest request) {
    Job job =
        importJobService.submitUserImport(
            userContext, request.fileAssetId(), request.idempotencyKey());
    return ImportJobResponse.from(job);
  }

  @GetMapping("/{id}")
  @RequiresAccess(type = ResourceType.JOB, action = Action.READ)
  public ImportJobResponse getImportJob(@PathVariable UUID id) {
    return ImportJobResponse.from(importJobService.getJob(id));
  }
}
