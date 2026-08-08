package com.company.platform.template.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.file.application.FileDownloadResult;
import com.company.platform.file.application.FileDownloadService;
import com.company.platform.file.application.FileUploadInitiation;
import com.company.platform.file.application.FileUploadService;
import com.company.platform.file.application.InitiateFileUploadCommand;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.UserContext;
import com.company.platform.shared.lock.AdvisoryLockService;
import com.company.platform.template.domain.FileTemplate;
import com.company.platform.template.domain.FileTemplateNotFoundException;
import com.company.platform.template.domain.FileTemplateRepository;
import java.time.Instant;
import java.util.Optional;
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
        .recordIndependently(
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
        .recordIndependently(
            eq(adminId),
            eq("TEMPLATE_UPLOAD_INITIATE"),
            eq("FILE_TEMPLATE"),
            any(),
            eq(AuditResult.SUCCESS));
  }

  @Test
  void should_returnLatestVersion_when_versionParamIsNull() {
    UUID userId = UUID.randomUUID();
    UserContext user = new UserContext(userId, Set.of("MEMBER"));
    FileTemplate latest =
        new FileTemplate(
            UUID.randomUUID(), "MEMBER", "MEMBER_IMPORT", 3, UUID.randomUUID(), "desc", userId);
    when(fileTemplateRepository
            .findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc(
                "MEMBER", "MEMBER_IMPORT"))
        .thenReturn(Optional.of(latest));
    when(fileDownloadService.generateDownloadUrl(latest.getFileId()))
        .thenReturn(new FileDownloadResult("https://minio/latest", Instant.now()));

    FileDownloadResult result =
        fileTemplateService.generateDownloadUrl(user, "MEMBER", "MEMBER_IMPORT", null);

    assertThat(result.downloadUrl()).isEqualTo("https://minio/latest");
    verify(auditLogService)
        .record(
            eq(userId),
            eq("TEMPLATE_DOWNLOADED"),
            eq("FILE_TEMPLATE"),
            eq(latest.getId()),
            eq(AuditResult.SUCCESS));
  }

  @Test
  void should_returnRequestedVersion_when_versionParamProvided() {
    UUID userId = UUID.randomUUID();
    UserContext user = new UserContext(userId, Set.of("MEMBER"));
    FileTemplate v1 =
        new FileTemplate(
            UUID.randomUUID(), "MEMBER", "MEMBER_IMPORT", 1, UUID.randomUUID(), "desc", userId);
    when(fileTemplateRepository.findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(
            "MEMBER", "MEMBER_IMPORT", 1))
        .thenReturn(Optional.of(v1));
    when(fileDownloadService.generateDownloadUrl(v1.getFileId()))
        .thenReturn(new FileDownloadResult("https://minio/v1", Instant.now()));

    FileDownloadResult result =
        fileTemplateService.generateDownloadUrl(user, "MEMBER", "MEMBER_IMPORT", 1);

    assertThat(result.downloadUrl()).isEqualTo("https://minio/v1");
  }

  @Test
  void should_throwFileTemplateNotFoundException_when_noVersionMatches() {
    UserContext user = new UserContext(UUID.randomUUID(), Set.of("MEMBER"));
    when(fileTemplateRepository
            .findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc(
                "MEMBER", "MEMBER_IMPORT"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> fileTemplateService.generateDownloadUrl(user, "MEMBER", "MEMBER_IMPORT", null))
        .isInstanceOf(FileTemplateNotFoundException.class);
  }
}
