package com.company.platform.import_.application;

import com.company.platform.job.application.JobSubmissionService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobNotFoundException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.ResourceAuthorizationService;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Import gắn với 1 file_asset cụ thể (đã có owner_id) nên dùng đúng ResourceAuthorizationService
 * (owner hoặc ADMIN) thay vì tự check role thủ công như ExportJobService — nhờ vậy DENIED cũng tự
 * động được ghi audit_logs qua ResourceAccessAuditAspect, không cần gọi thủ công.
 */
@Service
public class ImportJobService {

  private final JobSubmissionService jobSubmissionService;
  private final JobRepository jobRepository;
  private final ResourceAuthorizationService resourceAuthorizationService;
  private final ObjectMapper objectMapper;

  public ImportJobService(
      JobSubmissionService jobSubmissionService,
      JobRepository jobRepository,
      ResourceAuthorizationService resourceAuthorizationService,
      ObjectMapper objectMapper) {
    this.jobSubmissionService = jobSubmissionService;
    this.jobRepository = jobRepository;
    this.resourceAuthorizationService = resourceAuthorizationService;
    this.objectMapper = objectMapper;
  }

  public Job submitUserImport(UserContext user, UUID fileAssetId, String idempotencyKey) {
    boolean allowed =
        resourceAuthorizationService.canAccess(
            user, ResourceType.FILE_ASSET, fileAssetId.toString(), Action.WRITE);
    if (!allowed) {
      throw new AccessDeniedException(ResourceType.FILE_ASSET, fileAssetId.toString());
    }
    ObjectNode payload = objectMapper.createObjectNode().put("fileAssetId", fileAssetId.toString());
    return jobSubmissionService.submit(JobType.IMPORT_USER, payload, idempotencyKey, user.userId());
  }

  public Job getJob(UUID jobId) {
    return jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
  }
}
