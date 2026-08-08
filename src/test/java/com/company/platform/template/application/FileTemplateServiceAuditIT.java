package com.company.platform.template.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.shared.audit.AuditLog;
import com.company.platform.shared.audit.AuditLogRepository;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.UserContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression test cho bug: {@code FileTemplateService.initiateUpload} là {@code @Transactional}, và
 * {@code requireAdmin} ghi audit DENIED rồi throw {@code AccessDeniedException} (unchecked) ngay
 * trong transaction đó. Trước fix, exception unchecked đánh dấu transaction rollback-only nên dòng
 * audit DENIED vừa ghi bị cuốn theo, không bao giờ tới DB thật — Mockito-based {@code
 * FileTemplateServiceTest} không bắt được lỗi này vì mock không tham gia transaction thật của
 * Spring. Test này dùng Postgres thật (Testcontainers) + Spring transaction manager thật để chứng
 * minh: sau fix (REQUIRES_NEW ở {@code AuditLogService.record}), dòng audit DENIED persist dù
 * {@code initiateUpload} rollback ngay sau đó.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class FileTemplateServiceAuditIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private FileTemplateService fileTemplateService;
  @Autowired private AuditLogRepository auditLogRepository;

  @Test
  void should_persistDeniedAuditRecord_when_nonAdminInitiatesUpload_despiteTransactionRollback() {
    UUID memberId = UUID.randomUUID();
    UserContext member = new UserContext(memberId, Set.of("MEMBER"));
    String category = "MEMBER_" + UUID.randomUUID();
    String templateCode = "MEMBER_IMPORT_" + UUID.randomUUID();
    InitiateTemplateUploadCommand command =
        new InitiateTemplateUploadCommand(category, templateCode, "t.csv", "text/csv", 100);

    assertThatThrownBy(() -> fileTemplateService.initiateUpload(member, command))
        .isInstanceOf(AccessDeniedException.class);

    UUID pseudoResourceId =
        UUID.nameUUIDFromBytes((category + ":" + templateCode).getBytes(StandardCharsets.UTF_8));
    List<AuditLog> logs = auditLogRepository.findByResourceId(pseudoResourceId);

    assertThat(logs).hasSize(1);
    AuditLog log = logs.get(0);
    assertThat(log.getActorId()).isEqualTo(memberId);
    assertThat(log.getAction()).isEqualTo("TEMPLATE_UPLOAD_INITIATE");
    assertThat(log.getResourceType()).isEqualTo("FILE_TEMPLATE");
    assertThat(log.getResult()).isEqualTo(AuditResult.DENIED);
  }
}
