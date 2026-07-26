package com.company.platform.export.application;

import com.company.platform.file.application.FileDownloadResult;
import com.company.platform.file.application.FileDownloadService;
import com.company.platform.job.application.JobSubmissionService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobNotFoundException;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobStatus;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Export toàn bộ app_users không lọc theo owner nên không dùng được rule owner_id == user.id ở mục
 * 9 — chỉ còn nhánh user.hasRole(ADMIN). Check ngay tại đây (tầng service gọi trực tiếp từ
 * Controller), trước khi tạo job, đúng nguyên tắc "Authorization luôn check ở tầng API".
 */
@Service
public class ExportJobService {

  private static final String ROLE_ADMIN = "ADMIN";

  private final JobSubmissionService jobSubmissionService;
  private final JobRepository jobRepository;
  private final ObjectMapper objectMapper;
  private final FileDownloadService fileDownloadService;

  public ExportJobService(
      JobSubmissionService jobSubmissionService,
      JobRepository jobRepository,
      ObjectMapper objectMapper,
      FileDownloadService fileDownloadService) {
    this.jobSubmissionService = jobSubmissionService;
    this.jobRepository = jobRepository;
    this.objectMapper = objectMapper;
    this.fileDownloadService = fileDownloadService;
  }

  public Job submitUserCsvExport(UserContext user, String idempotencyKey) {
    if (!user.roles().contains(ROLE_ADMIN)) {
      throw new AccessDeniedException(ResourceType.JOB, "EXPORT_USER_CSV");
    }
    ObjectNode emptyPayload = objectMapper.createObjectNode();
    return jobSubmissionService.submit(
        JobType.EXPORT_USER_CSV, emptyPayload, idempotencyKey, user.userId());
  }

  public ExportJobDetail getJob(UUID jobId) {
    Job job = jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    String downloadUrl = null;
    if (job.getStatus() == JobStatus.DONE) {
      downloadUrl =
          fileDownloadService
              .findDownloadUrlByJobId(jobId)
              .map(FileDownloadResult::downloadUrl)
              .orElse(null);
    }
    return new ExportJobDetail(job, downloadUrl);
  }
}
