package com.company.platform.template.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.file.application.FileDownloadService;
import com.company.platform.file.application.FileUploadInitiation;
import com.company.platform.file.application.FileUploadService;
import com.company.platform.file.application.InitiateFileUploadCommand;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.UserContext;
import com.company.platform.shared.lock.AdvisoryLockService;
import com.company.platform.template.domain.FileTemplateRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FileTemplateServiceTest {

  private final FileTemplateRepository fileTemplateRepository = mock(FileTemplateRepository.class);
  private final FileUploadService fileUploadService = mock(FileUploadService.class);
  private final FileDownloadService fileDownloadService = mock(FileDownloadService.class);
  private final AdvisoryLockService advisoryLockService = mock(AdvisoryLockService.class);
  private final AuditLogService auditLogService = mock(AuditLogService.class);
  private final FileTemplateService fileTemplateService =
      new FileTemplateService(
          fileTemplateRepository,
          fileUploadService,
          fileDownloadService,
          advisoryLockService,
          auditLogService);

  @Test
  void should_throwAccessDeniedException_and_recordDeniedAudit_when_userIsNotAdmin() {
    UserContext member = new UserContext(UUID.randomUUID(), Set.of("MEMBER"));
    InitiateTemplateUploadCommand command =
        new InitiateTemplateUploadCommand("MEMBER", "MEMBER_IMPORT", "t.csv", "text/csv", 100);

    assertThatThrownBy(() -> fileTemplateService.initiateUpload(member, command))
        .isInstanceOf(AccessDeniedException.class);

    verify(fileUploadService, never()).initiateUpload(any());
    verify(auditLogService)
        .record(
            eq(member.userId()),
            eq("TEMPLATE_UPLOAD_INITIATE"),
            eq("FILE_TEMPLATE"),
            any(),
            eq(AuditResult.DENIED));
  }

  @Test
  void should_delegateToFileUploadService_and_recordSuccessAudit_when_userIsAdmin() {
    UUID adminId = UUID.randomUUID();
    UserContext admin = new UserContext(adminId, Set.of("ADMIN"));
    InitiateTemplateUploadCommand command =
        new InitiateTemplateUploadCommand(
            "MEMBER", "MEMBER_IMPORT", "member-import.xlsx", "text/csv", 2048);
    UUID fileAssetId = UUID.randomUUID();
    when(fileUploadService.initiateUpload(any()))
        .thenReturn(new FileUploadInitiation(fileAssetId, "http://minio/put-url", Instant.now()));

    TemplateUploadInitiation result = fileTemplateService.initiateUpload(admin, command);

    assertThat(result.fileAssetId()).isEqualTo(fileAssetId);
    assertThat(result.uploadUrl()).isEqualTo("http://minio/put-url");

    ArgumentCaptor<InitiateFileUploadCommand> captor =
        ArgumentCaptor.forClass(InitiateFileUploadCommand.class);
    verify(fileUploadService).initiateUpload(captor.capture());
    assertThat(captor.getValue().categoryCode()).isEqualTo("FILE_TEMPLATE");
    assertThat(captor.getValue().ownerType()).isEqualTo("FILE_TEMPLATE");
    assertThat(captor.getValue().ownerId()).isNotNull();
    assertThat(captor.getValue().uploadedBy()).isEqualTo(adminId);
    assertThat(captor.getValue().originalFilename()).isEqualTo("member-import.xlsx");

    verify(auditLogService)
        .record(
            eq(adminId),
            eq("TEMPLATE_UPLOAD_INITIATE"),
            eq("FILE_TEMPLATE"),
            any(),
            eq(AuditResult.SUCCESS));
  }
}
