# File Template Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cho phép ADMIN upload file template theo `(category, templateCode)` với version tự tăng, và cho phép mọi user đã đăng nhập tải file template (mặc định bản mới nhất, hoặc version cụ thể) qua presigned URL.

**Architecture:** Module mới `com.company.platform.template` (domain/application/api), tái dùng nguyên `FileUploadService`/`FileDownloadService` của module `file` cho toàn bộ phần presigned URL/verify — không viết lại logic đó. Version được tính trong transaction của bước confirm, bảo vệ bằng `pg_try_advisory_xact_lock` (tái dùng `AdvisoryLockService` có sẵn) để tránh đụng version khi nhiều lần confirm chạy đồng thời cho cùng `(category, templateCode)`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL, MinIO, Liquibase, JUnit 5 + Mockito + AssertJ + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-08-file-template-management-design.md`

## Global Constraints

- Authorization (quyền ADMIN cho upload) check ở tầng application service gọi trực tiếp từ Controller trong cùng request — không check trong worker (không áp dụng ở đây, tính năng này không có worker/job). Precedent: `ExportJobService.submitUserCsvExport`.
- Status/version transition dùng transaction + advisory lock, không đọc-rồi-ghi không bảo vệ.
- Business code (`template/`) không import package Kafka/Redis/MinIO trực tiếp — chỉ gọi qua `file.application` (`FileUploadService`, `FileDownloadService`).
- Object key file vẫn là UUID (đã đảm bảo bởi `file` module, không đổi gì ở đó).
- Presigned download URL chỉ sinh khi `file_assets.storage_status = CLEAN` — tái dùng nguyên `FileDownloadService.generateDownloadUrl`, không viết lại.
- Module `template` chỉ gọi vào package `file.application`/`file.domain` (public), không đụng `file.internal` (không tồn tại `internal` trong `file` hiện tại, nhưng vẫn không gọi `file.storage.*` trực tiếp).
- Format bằng `mvn spotless:apply` trước khi coi task là xong; `mvn clean install` fail nếu chưa format.
- Tên test: `should_<expected>_when_<condition>`. Test liên quan DB transaction/CAS/race condition dùng Testcontainers (Postgres/MinIO thật), không mock.
- Record (Java record) cho mọi DTO/command bất biến. Constructor injection only.

---

### Task 1: DB schema — `file_template_manager` + seed category `FILE_TEMPLATE`

**Files:**
- Create: `src/main/resources/db/changelog/changes/009-init-file-template-manager.yaml`
- Create: `src/main/resources/db/changelog/changes/010-seed-file-category-file-template.yaml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`

**Interfaces:**
- Produces: bảng `file_template_manager` (cột: `id UUID PK`, `category VARCHAR(100)`, `template_code VARCHAR(100)`, `version INT`, `file_id UUID FK→file_assets.id`, `description VARCHAR(500)`, `created_by UUID`, `created_at TIMESTAMP`, `updated_by UUID`, `updated_at TIMESTAMP`, `deleted_at TIMESTAMP`), unique constraint `(category, template_code, version)`. Row `file_categories.code = 'FILE_TEMPLATE'`.

- [ ] **Step 1: Tạo changeset bảng `file_template_manager`**

`src/main/resources/db/changelog/changes/009-init-file-template-manager.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 009-1-create-table-file-template-manager
      author: platform
      changes:
        - createTable:
            tableName: file_template_manager
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: category
                  type: VARCHAR(100)
                  constraints:
                    nullable: false
              - column:
                  name: template_code
                  type: VARCHAR(100)
                  constraints:
                    nullable: false
              - column:
                  name: version
                  type: INT
                  constraints:
                    nullable: false
              - column:
                  name: file_id
                  type: UUID
                  constraints:
                    nullable: false
              - column:
                  name: description
                  type: VARCHAR(500)
              - column:
                  name: created_by
                  type: UUID
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: TIMESTAMP
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
              - column:
                  name: updated_by
                  type: UUID
              - column:
                  name: updated_at
                  type: TIMESTAMP
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
              - column:
                  name: deleted_at
                  type: TIMESTAMP

  - changeSet:
      id: 009-2-unique-file-template-manager-category-code-version
      author: platform
      changes:
        - addUniqueConstraint:
            tableName: file_template_manager
            columnNames: category, template_code, version
            constraintName: uk_file_template_manager_category_code_version

  - changeSet:
      id: 009-3-fk-file-template-manager-file-id
      author: platform
      changes:
        - addForeignKeyConstraint:
            baseTableName: file_template_manager
            baseColumnNames: file_id
            referencedTableName: file_assets
            referencedColumnNames: id
            constraintName: fk_file_template_manager_file_id
```

- [ ] **Step 2: Tạo changeset seed category `FILE_TEMPLATE`**

`src/main/resources/db/changelog/changes/010-seed-file-category-file-template.yaml`:

```yaml
databaseChangeLog:
  # FILE_TEMPLATE là category thật mà FileTemplateService cần để hoạt động (bucket/mime/size dùng
  # chung cho mọi file template bất kể category/templateCode nghiệp vụ), không phải dữ liệu demo —
  # cùng nhóm với 006/007/008. retention_days = NULL vì template là tài liệu tham chiếu, không hết
  # hạn tự động.
  - changeSet:
      id: 010-1-seed-file-category-file-template
      author: platform
      changes:
        - sql:
            sql: |
              INSERT INTO file_categories (code, bucket_name, allowed_mime_types, max_size_bytes, require_virus_scan, is_public, retention_days)
              VALUES ('FILE_TEMPLATE', 'file-templates', '["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv"]'::jsonb, 10485760, false, true, NULL);
```

- [ ] **Step 3: Đăng ký 2 changeset vào master changelog**

Modify `src/main/resources/db/changelog/db.changelog-master.yaml`, thêm vào cuối:

```yaml
  - include:
      file: db/changelog/changes/009-init-file-template-manager.yaml
  - include:
      file: db/changelog/changes/010-seed-file-category-file-template.yaml
```

- [ ] **Step 4: Verify Liquibase syntax bằng cách chạy build**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (Liquibase changelog chỉ thật sự chạy khi Spring context khởi động — sẽ được verify đầy đủ ở Task 2's IT test).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/changelog/changes/009-init-file-template-manager.yaml \
        src/main/resources/db/changelog/changes/010-seed-file-category-file-template.yaml \
        src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "Add DB schema for file template management (file_template_manager + FILE_TEMPLATE category)"
```

---

### Task 2: Domain layer — `FileTemplate` entity, `FileTemplateRepository`, `FileTemplateNotFoundException`

**Files:**
- Create: `src/main/java/com/company/platform/template/domain/FileTemplate.java`
- Create: `src/main/java/com/company/platform/template/domain/FileTemplateRepository.java`
- Create: `src/main/java/com/company/platform/template/domain/FileTemplateNotFoundException.java`
- Create: `src/main/java/com/company/platform/template/domain/TemplateVersionLockTimeoutException.java`
- Test: `src/test/java/com/company/platform/template/domain/FileTemplateRepositoryIT.java`

**Interfaces:**
- Produces:
  - `FileTemplate(UUID id, String category, String templateCode, int version, UUID fileId, String description, UUID createdBy)` — constructor.
  - `FileTemplate.getId()/getCategory()/getTemplateCode()/getVersion()/getFileId()/getDescription()/getCreatedBy()/getCreatedAt()/getUpdatedAt()` — getters (Lombok `@Getter`).
  - `FileTemplateRepository.findMaxVersion(String category, String templateCode): int`
  - `FileTemplateRepository.findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(String, String, int): Optional<FileTemplate>`
  - `FileTemplateRepository.findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc(String, String): Optional<FileTemplate>`
  - `FileTemplateNotFoundException(String category, String templateCode)` và `FileTemplateNotFoundException(String category, String templateCode, int version)`.
  - `TemplateVersionLockTimeoutException(String category, String templateCode)`.

- [ ] **Step 1: Viết `FileTemplate` entity**

`src/main/java/com/company/platform/template/domain/FileTemplate.java`:

```java
package com.company.platform.template.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "file_template_manager")
@Getter
public class FileTemplate {

  @Id private UUID id;

  @Column(name = "category", nullable = false)
  private String category;

  @Column(name = "template_code", nullable = false)
  private String templateCode;

  @Column(name = "version", nullable = false)
  private int version;

  @Column(name = "file_id", nullable = false)
  private UUID fileId;

  @Column(name = "description")
  private String description;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected FileTemplate() {}

  public FileTemplate(
      UUID id,
      String category,
      String templateCode,
      int version,
      UUID fileId,
      String description,
      UUID createdBy) {
    Instant now = Instant.now();
    this.id = id;
    this.category = category;
    this.templateCode = templateCode;
    this.version = version;
    this.fileId = fileId;
    this.description = description;
    this.createdBy = createdBy;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
```

- [ ] **Step 2: Viết `FileTemplateRepository`**

`src/main/java/com/company/platform/template/domain/FileTemplateRepository.java`:

```java
package com.company.platform.template.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileTemplateRepository extends JpaRepository<FileTemplate, UUID> {

  @Query(
      "SELECT COALESCE(MAX(t.version), 0) FROM FileTemplate t "
          + "WHERE t.category = :category AND t.templateCode = :templateCode")
  int findMaxVersion(@Param("category") String category, @Param("templateCode") String templateCode);

  Optional<FileTemplate> findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(
      String category, String templateCode, int version);

  Optional<FileTemplate> findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc(
      String category, String templateCode);
}
```

- [ ] **Step 3: Viết 2 exception**

`src/main/java/com/company/platform/template/domain/FileTemplateNotFoundException.java`:

```java
package com.company.platform.template.domain;

public class FileTemplateNotFoundException extends RuntimeException {

  public FileTemplateNotFoundException(String category, String templateCode) {
    super(
        "Không tìm thấy file template: category=%s, templateCode=%s"
            .formatted(category, templateCode));
  }

  public FileTemplateNotFoundException(String category, String templateCode, int version) {
    super(
        "Không tìm thấy file template: category=%s, templateCode=%s, version=%d"
            .formatted(category, templateCode, version));
  }
}
```

`src/main/java/com/company/platform/template/domain/TemplateVersionLockTimeoutException.java`:

```java
package com.company.platform.template.domain;

public class TemplateVersionLockTimeoutException extends RuntimeException {

  public TemplateVersionLockTimeoutException(String category, String templateCode) {
    super(
        "Không lấy được khoá version cho category=%s, templateCode=%s sau nhiều lần thử, thử lại"
            .formatted(category, templateCode));
  }
}
```

- [ ] **Step 4: Viết integration test cho repository (Testcontainers Postgres)**

`src/test/java/com/company/platform/template/domain/FileTemplateRepositoryIT.java`:

```java
package com.company.platform.template.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class FileTemplateRepositoryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private FileTemplateRepository fileTemplateRepository;
  @Autowired private FileAssetRepository fileAssetRepository;

  private String category;
  private String templateCode;

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    category = "TEST_CATEGORY_" + suffix;
    templateCode = "TEST_CODE_" + suffix;
  }

  @Test
  void should_returnZero_when_noVersionExistsYet() {
    assertThat(fileTemplateRepository.findMaxVersion(category, templateCode)).isEqualTo(0);
  }

  @Test
  void should_returnHighestVersion_when_multipleVersionsExist() {
    saveTemplate(1);
    saveTemplate(2);
    saveTemplate(3);

    assertThat(fileTemplateRepository.findMaxVersion(category, templateCode)).isEqualTo(3);
  }

  @Test
  void should_returnLatestVersion_when_findingWithoutExplicitVersion() {
    saveTemplate(1);
    FileTemplate latest = saveTemplate(2);

    assertThat(
            fileTemplateRepository
                .findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc(
                    category, templateCode))
        .map(FileTemplate::getId)
        .contains(latest.getId());
  }

  @Test
  void should_returnRequestedVersion_when_versionSpecified() {
    FileTemplate v1 = saveTemplate(1);
    saveTemplate(2);

    assertThat(
            fileTemplateRepository.findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(
                category, templateCode, 1))
        .map(FileTemplate::getId)
        .contains(v1.getId());
  }

  @Test
  void should_returnEmpty_when_versionDoesNotExist() {
    saveTemplate(1);

    assertThat(
            fileTemplateRepository.findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(
                category, templateCode, 99))
        .isEmpty();
  }

  private FileTemplate saveTemplate(int version) {
    FileAsset fileAsset =
        fileAssetRepository.save(
            new FileAsset(
                UUID.randomUUID(),
                "FILE_TEMPLATE",
                "file-templates",
                UUID.randomUUID().toString(),
                "template.csv",
                "text/csv",
                100,
                "FILE_TEMPLATE",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null));
    return fileTemplateRepository.save(
        new FileTemplate(
            UUID.randomUUID(),
            category,
            templateCode,
            version,
            fileAsset.getId(),
            "mô tả test v" + version,
            UUID.randomUUID()));
  }
}
```

- [ ] **Step 5: Chạy test**

Run: `mvn verify -Pintegration-test -Dit.test=FileTemplateRepositoryIT` (cần Docker chạy sẵn cho Testcontainers)
Expected: PASS (5 test).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/platform/template/domain/ \
        src/test/java/com/company/platform/template/domain/
git commit -m "Add FileTemplate entity, repository and domain exceptions"
```

---

### Task 3: `FileTemplateService.initiateUpload` — quyền ADMIN + delegate sang `file` module

**Files:**
- Modify: `src/main/java/com/company/platform/shared/auth/ResourceType.java`
- Create: `src/main/java/com/company/platform/template/application/InitiateTemplateUploadCommand.java`
- Create: `src/main/java/com/company/platform/template/application/TemplateUploadInitiation.java`
- Create: `src/main/java/com/company/platform/template/application/FileTemplateService.java`
- Test: `src/test/java/com/company/platform/template/application/FileTemplateServiceTest.java`

**Interfaces:**
- Consumes: `FileUploadService.initiateUpload(InitiateFileUploadCommand): FileUploadInitiation` (`file.application`), `AuditLogService.record(UUID, String, String, UUID, AuditResult)` (`shared.audit`), `AdvisoryLockService.tryLock(long): boolean` (`shared.lock`), `UserContext.userId()/roles()` (`shared.auth`).
- Produces:
  - `InitiateTemplateUploadCommand(String category, String templateCode, String originalFilename, String mimeType, long sizeBytes)`
  - `TemplateUploadInitiation(UUID fileAssetId, String uploadUrl, Instant expiresAt)`
  - `FileTemplateService.initiateUpload(UserContext user, InitiateTemplateUploadCommand command): TemplateUploadInitiation` — dùng ở Task 6.
  - `ResourceType.FILE_TEMPLATE` — enum constant mới, dùng cho `AccessDeniedException`.

- [ ] **Step 1: Thêm `ResourceType.FILE_TEMPLATE`**

Modify `src/main/java/com/company/platform/shared/auth/ResourceType.java`:

```java
package com.company.platform.shared.auth;

public enum ResourceType {
  JOB,
  FILE_ASSET,
  FILE_TEMPLATE
}
```

(Không có `ResourceOwnerLookup` cho `FILE_TEMPLATE` — không sao, vì quyền template check qua role thuần, không đi qua `ResourceAuthorizationService.canAccess(...)`. `ResourceType.FILE_TEMPLATE` chỉ dùng để gắn nhãn đúng loại resource trong `AccessDeniedException`.)

- [ ] **Step 2: Viết command + result record**

`src/main/java/com/company/platform/template/application/InitiateTemplateUploadCommand.java`:

```java
package com.company.platform.template.application;

public record InitiateTemplateUploadCommand(
    String category,
    String templateCode,
    String originalFilename,
    String mimeType,
    long sizeBytes) {}
```

`src/main/java/com/company/platform/template/application/TemplateUploadInitiation.java`:

```java
package com.company.platform.template.application;

import java.time.Instant;
import java.util.UUID;

public record TemplateUploadInitiation(UUID fileAssetId, String uploadUrl, Instant expiresAt) {}
```

- [ ] **Step 3: Viết test trước (mock, chưa có class `FileTemplateService`)**

`src/test/java/com/company/platform/template/application/FileTemplateServiceTest.java`:

```java
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
            eq(adminId), eq("TEMPLATE_UPLOAD_INITIATE"), eq("FILE_TEMPLATE"), any(), eq(AuditResult.SUCCESS));
  }
}
```

- [ ] **Step 4: Chạy test để thấy fail (chưa có `FileTemplateService`)**

Run: `mvn test -Dtest=FileTemplateServiceTest`
Expected: FAIL biên dịch — không tìm thấy class `FileTemplateService`.

- [ ] **Step 5: Viết `FileTemplateService` (chỉ phần `initiateUpload` + hạ tầng dùng chung cho các task sau)**

`src/main/java/com/company/platform/template/application/FileTemplateService.java`:

```java
package com.company.platform.template.application;

import com.company.platform.file.application.FileDownloadService;
import com.company.platform.file.application.FileUploadInitiation;
import com.company.platform.file.application.FileUploadService;
import com.company.platform.file.application.InitiateFileUploadCommand;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
import com.company.platform.shared.lock.AdvisoryLockService;
import com.company.platform.template.domain.FileTemplateRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý upload/download file template theo (category, templateCode), có version. Chỉ tái dùng
 * public API của module file (FileUploadService/FileDownloadService), không tự viết lại logic
 * presigned URL/verify — đúng nguyên tắc module boundary (mục 2 design doc gốc).
 *
 * <p>Quyền upload (initiate/confirm) yêu cầu role ADMIN, check ngay tại đây (application service
 * gọi trực tiếp từ Controller trong cùng request) — cùng pattern với {@code
 * ExportJobService#submitUserCsvExport}: role-check thuần, không owner-based nên không dùng được
 * {@code @RequiresAccess}/{@code ResourceOwnerLookup}. Khác với ExportJobService, chủ động ghi
 * audit_logs cho cả 2 nhánh ALLOW/DENIED (mục 9 design doc gốc).
 */
@Service
public class FileTemplateService {

  private static final String ROLE_ADMIN = "ADMIN";
  private static final String TEMPLATE_FILE_CATEGORY_CODE = "FILE_TEMPLATE";
  private static final String TEMPLATE_OWNER_TYPE = "FILE_TEMPLATE";
  private static final String AUDIT_RESOURCE_TYPE = "FILE_TEMPLATE";
  private static final String ACTION_UPLOAD_INITIATE = "TEMPLATE_UPLOAD_INITIATE";
  private static final int LOCK_MAX_ATTEMPTS = 20;
  private static final Duration LOCK_RETRY_DELAY = Duration.ofMillis(25);

  private final FileTemplateRepository fileTemplateRepository;
  private final FileUploadService fileUploadService;
  private final FileDownloadService fileDownloadService;
  private final AdvisoryLockService advisoryLockService;
  private final AuditLogService auditLogService;

  public FileTemplateService(
      FileTemplateRepository fileTemplateRepository,
      FileUploadService fileUploadService,
      FileDownloadService fileDownloadService,
      AdvisoryLockService advisoryLockService,
      AuditLogService auditLogService) {
    this.fileTemplateRepository = fileTemplateRepository;
    this.fileUploadService = fileUploadService;
    this.fileDownloadService = fileDownloadService;
    this.advisoryLockService = advisoryLockService;
    this.auditLogService = auditLogService;
  }

  @Transactional
  public TemplateUploadInitiation initiateUpload(
      UserContext user, InitiateTemplateUploadCommand command) {
    requireAdmin(user, ACTION_UPLOAD_INITIATE, command.category(), command.templateCode());

    UUID templateId = UUID.randomUUID();
    FileUploadInitiation initiation =
        fileUploadService.initiateUpload(
            new InitiateFileUploadCommand(
                TEMPLATE_FILE_CATEGORY_CODE,
                command.originalFilename(),
                command.mimeType(),
                command.sizeBytes(),
                TEMPLATE_OWNER_TYPE,
                templateId,
                user.userId()));

    return new TemplateUploadInitiation(
        initiation.fileAssetId(), initiation.uploadUrl(), initiation.expiresAt());
  }

  private void requireAdmin(UserContext user, String action, String category, String templateCode) {
    boolean isAdmin = user.roles().contains(ROLE_ADMIN);
    auditLogService.record(
        user.userId(),
        action,
        AUDIT_RESOURCE_TYPE,
        pseudoResourceId(category, templateCode),
        isAdmin ? AuditResult.SUCCESS : AuditResult.DENIED);
    if (!isAdmin) {
      throw new AccessDeniedException(ResourceType.FILE_TEMPLATE, category + ":" + templateCode);
    }
  }

  // UUID ổn định theo (category, templateCode) dùng làm resourceId audit cho bước check quyền —
  // tại initiate chưa có file_template_manager.id thật (row chỉ tạo ở bước confirm, Task 4).
  private UUID pseudoResourceId(String category, String templateCode) {
    return UUID.nameUUIDFromBytes((category + ":" + templateCode).getBytes(StandardCharsets.UTF_8));
  }
}
```

(Các field/hằng số `fileTemplateRepository`, `fileDownloadService`, `advisoryLockService`, `LOCK_MAX_ATTEMPTS`, `LOCK_RETRY_DELAY` chưa dùng ở task này nhưng cần khai báo sẵn trong constructor vì Task 4/5 sẽ bổ sung method dùng chúng — tránh phải sửa lại chữ ký constructor giữa các task.)

- [ ] **Step 6: Chạy lại test, xác nhận PASS**

Run: `mvn test -Dtest=FileTemplateServiceTest`
Expected: PASS (2 test). Có thể có warning "unused field" cho `fileTemplateRepository`/`fileDownloadService`/`advisoryLockService`/`LOCK_MAX_ATTEMPTS`/`LOCK_RETRY_DELAY` — bỏ qua, sẽ dùng ở Task 4/5.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/platform/shared/auth/ResourceType.java \
        src/main/java/com/company/platform/template/application/ \
        src/test/java/com/company/platform/template/application/FileTemplateServiceTest.java
git commit -m "Add FileTemplateService.initiateUpload with ADMIN-only access check + audit"
```

---

### Task 4: `FileTemplateService.confirmUpload` — version tự tăng, khoá advisory lock

**Files:**
- Create: `src/main/java/com/company/platform/template/application/ConfirmTemplateUploadCommand.java`
- Modify: `src/main/java/com/company/platform/template/application/FileTemplateService.java`
- Test: `src/test/java/com/company/platform/template/application/FileTemplateServiceIT.java`

**Interfaces:**
- Consumes: `FileUploadService.confirmUpload(UUID, String): FileAsset` (`file.application`), `FileAsset.getOwnerId()/getId()` (`file.domain`).
- Produces: `ConfirmTemplateUploadCommand(UUID fileAssetId, String category, String templateCode, String checksumSha256, String description)`, `FileTemplateService.confirmUpload(UserContext, ConfirmTemplateUploadCommand): FileTemplate` — dùng ở Task 6.

- [ ] **Step 1: Viết command record**

`src/main/java/com/company/platform/template/application/ConfirmTemplateUploadCommand.java`:

```java
package com.company.platform.template.application;

import java.util.UUID;

public record ConfirmTemplateUploadCommand(
    UUID fileAssetId, String category, String templateCode, String checksumSha256, String description) {}
```

- [ ] **Step 2: Viết integration test trước (Testcontainers Postgres + MinIO)**

`src/test/java/com/company/platform/template/application/FileTemplateServiceIT.java`:

```java
package com.company.platform.template.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.UserContext;
import com.company.platform.template.domain.FileTemplate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class FileTemplateServiceIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void minioProperties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired private FileTemplateService fileTemplateService;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private String category;
  private String templateCode;

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    category = "TEST_CAT_" + suffix;
    templateCode = "TEST_CODE_" + suffix;
  }

  @Test
  void should_assignVersion1_when_firstConfirmForCategoryAndTemplateCode() throws Exception {
    FileTemplate fileTemplate = uploadVersion(adminUser(), "content-1");

    assertThat(fileTemplate.getVersion()).isEqualTo(1);
    assertThat(fileTemplate.getCategory()).isEqualTo(category);
    assertThat(fileTemplate.getTemplateCode()).isEqualTo(templateCode);
  }

  @Test
  void should_incrementVersion_when_confirmingSecondTimeForSameCategoryAndTemplateCode()
      throws Exception {
    uploadVersion(adminUser(), "content-1");
    FileTemplate second = uploadVersion(adminUser(), "content-2");

    assertThat(second.getVersion()).isEqualTo(2);
  }

  @Test
  void should_throwAccessDeniedException_when_confirmingAsNonAdmin() {
    UserContext member = new UserContext(UUID.randomUUID(), Set.of("MEMBER"));

    assertThatThrownBy(
            () ->
                fileTemplateService.confirmUpload(
                    member,
                    new ConfirmTemplateUploadCommand(
                        UUID.randomUUID(), category, templateCode, "sha", "desc")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void should_assignUniqueSequentialVersions_when_confirmingConcurrentlyForSamePair()
      throws Exception {
    int concurrentUploads = 5;
    List<Callable<FileTemplate>> tasks =
        IntStream.range(0, concurrentUploads)
            .<Callable<FileTemplate>>mapToObj(
                i -> () -> uploadVersion(adminUser(), "concurrent-content-" + i))
            .collect(Collectors.toList());

    ExecutorService executor = Executors.newFixedThreadPool(concurrentUploads);
    try {
      List<Future<FileTemplate>> futures = executor.invokeAll(tasks);
      List<Integer> versions = new ArrayList<>();
      for (Future<FileTemplate> future : futures) {
        versions.add(future.get(30, TimeUnit.SECONDS).getVersion());
      }
      assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    } finally {
      executor.shutdown();
    }
  }

  private UserContext adminUser() {
    return new UserContext(UUID.randomUUID(), Set.of("ADMIN"));
  }

  private FileTemplate uploadVersion(UserContext admin, String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    TemplateUploadInitiation initiation =
        fileTemplateService.initiateUpload(
            admin,
            new InitiateTemplateUploadCommand(
                category, templateCode, "template.csv", "text/csv", bytes.length));

    HttpResponse<Void> putResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(initiation.uploadUrl()))
                .PUT(BodyPublishers.ofByteArray(bytes))
                .build(),
            BodyHandlers.discarding());
    assertThat(putResponse.statusCode()).isEqualTo(200);

    return fileTemplateService.confirmUpload(
        admin,
        new ConfirmTemplateUploadCommand(
            initiation.fileAssetId(), category, templateCode, "fake-sha256", "desc: " + content));
  }
}
```

- [ ] **Step 3: Chạy test, xác nhận fail (chưa có `confirmUpload`)**

Run: `mvn verify -Pintegration-test -Dit.test=FileTemplateServiceIT`
Expected: FAIL biên dịch — không tìm thấy method `FileTemplateService.confirmUpload`.

- [ ] **Step 4: Thêm `confirmUpload` + `acquireVersionLock` vào `FileTemplateService`**

Modify `src/main/java/com/company/platform/template/application/FileTemplateService.java` — thêm import và method:

```java
import com.company.platform.file.domain.FileAsset;
import com.company.platform.template.domain.FileTemplate;
import com.company.platform.template.domain.TemplateVersionLockTimeoutException;
```

Thêm hằng số (cạnh `ACTION_UPLOAD_INITIATE`):

```java
  private static final String ACTION_UPLOAD_CONFIRM = "TEMPLATE_UPLOAD_CONFIRM";
```

Thêm method (sau `initiateUpload`):

```java
  /**
   * Version kế tiếp tính trong cùng transaction với FileUploadService#confirmUpload, bảo vệ bằng
   * pg_try_advisory_xact_lock (tái dùng AdvisoryLockService có sẵn) khoá theo (category,
   * templateCode) để 2 lần confirm đồng thời cho cùng cặp này không đụng version — lock tự nhả khi
   * transaction kết thúc (commit/rollback).
   */
  @Transactional
  public FileTemplate confirmUpload(UserContext user, ConfirmTemplateUploadCommand command) {
    requireAdmin(user, ACTION_UPLOAD_CONFIRM, command.category(), command.templateCode());

    FileAsset fileAsset =
        fileUploadService.confirmUpload(command.fileAssetId(), command.checksumSha256());
    UUID templateId = fileAsset.getOwnerId();

    acquireVersionLock(command.category(), command.templateCode());
    int nextVersion =
        fileTemplateRepository.findMaxVersion(command.category(), command.templateCode()) + 1;

    FileTemplate fileTemplate =
        new FileTemplate(
            templateId,
            command.category(),
            command.templateCode(),
            nextVersion,
            fileAsset.getId(),
            command.description(),
            user.userId());
    return fileTemplateRepository.save(fileTemplate);
  }

  // pg_try_advisory_xact_lock không block — thử lại có giới hạn (tối đa 20 lần x 25ms = 500ms)
  // thay vì bỏ qua như CleanupService, vì ở đây bắt buộc phải lấy được lock để version không bị
  // đụng, không chấp nhận được việc "bỏ qua tick này". 500ms đủ rộng vì phần giữ lock chỉ gồm 1
  // SELECT MAX + 1 INSERT (nhanh), phù hợp cho thao tác quản trị tần suất thấp.
  private void acquireVersionLock(String category, String templateCode) {
    long lockKey = (category + "|" + templateCode).hashCode();
    for (int attempt = 0; attempt < LOCK_MAX_ATTEMPTS; attempt++) {
      if (advisoryLockService.tryLock(lockKey)) {
        return;
      }
      sleepBeforeRetry();
    }
    throw new TemplateVersionLockTimeoutException(category, templateCode);
  }

  private void sleepBeforeRetry() {
    try {
      Thread.sleep(LOCK_RETRY_DELAY);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị interrupt khi chờ khoá version template", e);
    }
  }
```

- [ ] **Step 5: Chạy lại test, xác nhận PASS**

Run: `mvn verify -Pintegration-test -Dit.test=FileTemplateServiceIT`
Expected: PASS (4 test) — bao gồm test race condition với 5 thread đồng thời cho cùng `(category, templateCode)`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/platform/template/application/ \
        src/test/java/com/company/platform/template/application/FileTemplateServiceIT.java
git commit -m "Add FileTemplateService.confirmUpload with advisory-lock-protected version numbering"
```

---

### Task 5: `FileTemplateService.generateDownloadUrl` — mặc định latest, có audit

**Files:**
- Modify: `src/main/java/com/company/platform/template/application/FileTemplateService.java`
- Modify: `src/test/java/com/company/platform/template/application/FileTemplateServiceTest.java`

**Interfaces:**
- Consumes: `FileDownloadService.generateDownloadUrl(UUID): FileDownloadResult` (`file.application`), `FileTemplateRepository.findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc`/`findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull` (Task 2).
- Produces: `FileTemplateService.generateDownloadUrl(UserContext user, String category, String templateCode, Integer version): FileDownloadResult` — dùng ở Task 6.

- [ ] **Step 1: Thêm test cho `generateDownloadUrl` (mock)**

Modify `src/test/java/com/company/platform/template/application/FileTemplateServiceTest.java` — thêm import và test:

```java
import com.company.platform.file.application.FileDownloadResult;
import com.company.platform.template.domain.FileTemplate;
import com.company.platform.template.domain.FileTemplateNotFoundException;
import java.util.Optional;
```

Thêm vào cuối class (trước dấu `}` cuối):

```java
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
        .record(eq(userId), eq("TEMPLATE_DOWNLOADED"), eq("FILE_TEMPLATE"), eq(latest.getId()), eq(AuditResult.SUCCESS));
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
```

- [ ] **Step 2: Chạy test, xác nhận fail (chưa có `generateDownloadUrl`)**

Run: `mvn test -Dtest=FileTemplateServiceTest`
Expected: FAIL biên dịch — không tìm thấy method `FileTemplateService.generateDownloadUrl`.

- [ ] **Step 3: Thêm method vào `FileTemplateService`**

Modify `src/main/java/com/company/platform/template/application/FileTemplateService.java` — thêm import và hằng số:

```java
import com.company.platform.file.application.FileDownloadResult;
import com.company.platform.template.domain.FileTemplateNotFoundException;
```

```java
  private static final String ACTION_DOWNLOAD = "TEMPLATE_DOWNLOADED";
```

Thêm method (sau `confirmUpload`):

```java
  public FileDownloadResult generateDownloadUrl(
      UserContext user, String category, String templateCode, Integer version) {
    FileTemplate fileTemplate =
        version == null
            ? fileTemplateRepository
                .findFirstByCategoryAndTemplateCodeAndDeletedAtIsNullOrderByVersionDesc(
                    category, templateCode)
                .orElseThrow(() -> new FileTemplateNotFoundException(category, templateCode))
            : fileTemplateRepository
                .findFirstByCategoryAndTemplateCodeAndVersionAndDeletedAtIsNull(
                    category, templateCode, version)
                .orElseThrow(
                    () -> new FileTemplateNotFoundException(category, templateCode, version));

    FileDownloadResult result = fileDownloadService.generateDownloadUrl(fileTemplate.getFileId());
    auditLogService.record(
        user.userId(),
        ACTION_DOWNLOAD,
        AUDIT_RESOURCE_TYPE,
        fileTemplate.getId(),
        AuditResult.SUCCESS);
    return result;
  }
```

- [ ] **Step 4: Chạy lại test, xác nhận PASS**

Run: `mvn test -Dtest=FileTemplateServiceTest`
Expected: PASS (5 test).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/platform/template/application/FileTemplateService.java \
        src/test/java/com/company/platform/template/application/FileTemplateServiceTest.java
git commit -m "Add FileTemplateService.generateDownloadUrl with latest/specific version lookup"
```

---

### Task 6: API layer — `FileTemplateController`, DTO, exception handler

**Files:**
- Create: `src/main/java/com/company/platform/template/api/InitiateTemplateUploadRequest.java`
- Create: `src/main/java/com/company/platform/template/api/ConfirmTemplateUploadRequest.java`
- Create: `src/main/java/com/company/platform/template/api/TemplateUploadInitiationResponse.java`
- Create: `src/main/java/com/company/platform/template/api/FileTemplateResponse.java`
- Create: `src/main/java/com/company/platform/template/api/TemplateDownloadUrlResponse.java`
- Create: `src/main/java/com/company/platform/template/api/TemplateApiExceptionHandler.java`
- Create: `src/main/java/com/company/platform/template/api/FileTemplateController.java`
- Test: `src/test/java/com/company/platform/template/api/FileTemplateControllerTest.java`

**Interfaces:**
- Consumes: `FileTemplateService.initiateUpload/confirmUpload/generateDownloadUrl` (Task 3/4/5), `UserContext` (`shared.auth`).
- Produces: `POST /api/templates/upload/initiate`, `POST /api/templates/upload/confirm`, `GET /api/templates/download-url` — dùng ở Task 7.

- [ ] **Step 1: Viết DTO request/response**

`src/main/java/com/company/platform/template/api/InitiateTemplateUploadRequest.java`:

```java
package com.company.platform.template.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record InitiateTemplateUploadRequest(
    @Schema(description = "Cụm nghiệp vụ lớn.", example = "MEMBER") @NotBlank String category,
    @Schema(description = "Mã function.", example = "MEMBER_IMPORT") @NotBlank String templateCode,
    @Schema(description = "Tên file gốc, chỉ để hiển thị UI.", example = "member-import-template.xlsx")
        @NotBlank
        String originalFilename,
    @Schema(
            description = "MIME type, phải nằm trong allowed_mime_types của category FILE_TEMPLATE.",
            example = "text/csv")
        @NotBlank
        String mimeType,
    @Schema(description = "Kích thước file (bytes).", example = "20480") @Positive long sizeBytes) {}
```

`src/main/java/com/company/platform/template/api/ConfirmTemplateUploadRequest.java`:

```java
package com.company.platform.template.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConfirmTemplateUploadRequest(
    @Schema(description = "fileAssetId nhận được từ bước initiate.") @NotNull UUID fileAssetId,
    @Schema(description = "Cụm nghiệp vụ lớn, phải khớp bước initiate.", example = "MEMBER")
        @NotBlank
        String category,
    @Schema(description = "Mã function, phải khớp bước initiate.", example = "MEMBER_IMPORT")
        @NotBlank
        String templateCode,
    @Schema(description = "SHA-256 checksum client tự tính sau khi upload xong.")
        @NotBlank
        String checksumSha256,
    @Schema(description = "Mô tả version này (optional).", example = "Thêm cột ngày sinh")
        String description) {}
```

`src/main/java/com/company/platform/template/api/TemplateUploadInitiationResponse.java`:

```java
package com.company.platform.template.api;

import com.company.platform.template.application.TemplateUploadInitiation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record TemplateUploadInitiationResponse(
    @Schema(description = "ID file asset vừa tạo — dùng ở bước confirm.") UUID fileAssetId,
    @Schema(description = "Presigned PUT URL — client upload thẳng lên MinIO.") String uploadUrl,
    @Schema(description = "Thời điểm presigned URL hết hạn.") Instant expiresAt) {

  public static TemplateUploadInitiationResponse from(TemplateUploadInitiation initiation) {
    return new TemplateUploadInitiationResponse(
        initiation.fileAssetId(), initiation.uploadUrl(), initiation.expiresAt());
  }
}
```

`src/main/java/com/company/platform/template/api/FileTemplateResponse.java`:

```java
package com.company.platform.template.api;

import com.company.platform.template.domain.FileTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record FileTemplateResponse(
    @Schema(description = "ID version template vừa tạo.") UUID id,
    @Schema(description = "Cụm nghiệp vụ lớn.", example = "MEMBER") String category,
    @Schema(description = "Mã function.", example = "MEMBER_IMPORT") String templateCode,
    @Schema(description = "Version vừa tạo, tăng dần theo (category, templateCode).", example = "1")
        int version,
    @Schema(description = "Mô tả version này.") String description,
    @Schema(description = "Thời điểm tạo version này.") Instant createdAt) {

  public static FileTemplateResponse from(FileTemplate fileTemplate) {
    return new FileTemplateResponse(
        fileTemplate.getId(),
        fileTemplate.getCategory(),
        fileTemplate.getTemplateCode(),
        fileTemplate.getVersion(),
        fileTemplate.getDescription(),
        fileTemplate.getCreatedAt());
  }
}
```

`src/main/java/com/company/platform/template/api/TemplateDownloadUrlResponse.java`:

```java
package com.company.platform.template.api;

import com.company.platform.file.application.FileDownloadResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record TemplateDownloadUrlResponse(
    @Schema(description = "Presigned GET URL, thời hạn ngắn.") String downloadUrl,
    @Schema(description = "Thời điểm presigned URL hết hạn.") Instant expiresAt) {

  public static TemplateDownloadUrlResponse from(FileDownloadResult result) {
    return new TemplateDownloadUrlResponse(result.downloadUrl(), result.expiresAt());
  }
}
```

- [ ] **Step 2: Viết `TemplateApiExceptionHandler`**

`src/main/java/com/company/platform/template/api/TemplateApiExceptionHandler.java`:

```java
package com.company.platform.template.api;

import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileCategoryNotFoundException;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.FileVerificationFailedException;
import com.company.platform.file.domain.InvalidFileAssetStateException;
import com.company.platform.template.domain.FileTemplateNotFoundException;
import com.company.platform.template.domain.TemplateVersionLockTimeoutException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Xử lý riêng cho package template.api — FileApiExceptionHandler chỉ scope basePackages =
 * "com.company.platform.file.api" nên không bắt được exception ném ra từ file module khi nó được
 * gọi xuyên qua FileTemplateController (initiate/confirm đều delegate sang FileUploadService).
 */
@RestControllerAdvice(basePackages = "com.company.platform.template.api")
public class TemplateApiExceptionHandler {

  @ExceptionHandler(FileTemplateNotFoundException.class)
  public ResponseEntity<ApiError> handleTemplateNotFound(FileTemplateNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(TemplateVersionLockTimeoutException.class)
  public ResponseEntity<ApiError> handleVersionLockTimeout(TemplateVersionLockTimeoutException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(FileCategoryNotFoundException.class)
  public ResponseEntity<ApiError> handleCategoryNotFound(FileCategoryNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(FileAssetNotFoundException.class)
  public ResponseEntity<ApiError> handleAssetNotFound(FileAssetNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(FileValidationException.class)
  public ResponseEntity<ApiError> handleValidation(FileValidationException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(InvalidFileAssetStateException.class)
  public ResponseEntity<ApiError> handleInvalidState(InvalidFileAssetStateException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(FileVerificationFailedException.class)
  public ResponseEntity<ApiError> handleVerificationFailed(FileVerificationFailedException e) {
    return error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
  }

  @ExceptionHandler(FileNotAvailableForDownloadException.class)
  public ResponseEntity<ApiError> handleNotAvailableForDownload(
      FileNotAvailableForDownloadException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  private ResponseEntity<ApiError> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(new ApiError(status.value(), message, Instant.now()));
  }

  public record ApiError(int status, String message, Instant timestamp) {}
}
```

- [ ] **Step 3: Viết `FileTemplateController`**

`src/main/java/com/company/platform/template/api/FileTemplateController.java`:

```java
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
  public FileTemplateResponse confirmUpload(@Valid @RequestBody ConfirmTemplateUploadRequest request) {
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
    FileDownloadResult result =
        fileTemplateService.generateDownloadUrl(userContext, category, templateCode, version);
    return TemplateDownloadUrlResponse.from(result);
  }
}
```

- [ ] **Step 4: Viết `@WebMvcTest`**

`src/test/java/com/company/platform/template/api/FileTemplateControllerTest.java`:

```java
package com.company.platform.template.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContextConfig;
import com.company.platform.template.application.FileTemplateService;
import com.company.platform.template.application.TemplateUploadInitiation;
import com.company.platform.template.domain.FileTemplateNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileTemplateController.class)
@Import(UserContextConfig.class)
class FileTemplateControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FileTemplateService fileTemplateService;

  private static final String INITIATE_BODY =
      """
      {
        "category": "MEMBER",
        "templateCode": "MEMBER_IMPORT",
        "originalFilename": "member-import-template.xlsx",
        "mimeType": "text/csv",
        "sizeBytes": 20480
      }
      """;

  @Test
  void should_return401_when_userIdHeaderIsMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INITIATE_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_return403_when_serviceThrowsAccessDenied_onInitiate() throws Exception {
    when(fileTemplateService.initiateUpload(any(), any()))
        .thenThrow(new AccessDeniedException(ResourceType.FILE_TEMPLATE, "MEMBER:MEMBER_IMPORT"));

    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(INITIATE_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_return200_when_initiateUploadSucceeds() throws Exception {
    when(fileTemplateService.initiateUpload(any(), any()))
        .thenReturn(
            new TemplateUploadInitiation(UUID.randomUUID(), "http://minio/put-url", Instant.now()));

    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(INITIATE_BODY))
        .andExpect(status().isOk());
  }

  @Test
  void should_return404_when_downloadTemplateNotFound() throws Exception {
    when(fileTemplateService.generateDownloadUrl(any(), any(), any(), any()))
        .thenThrow(new FileTemplateNotFoundException("MEMBER", "MEMBER_IMPORT"));

    mockMvc
        .perform(
            get("/api/templates/download-url")
                .header("X-User-Id", UUID.randomUUID().toString())
                .param("category", "MEMBER")
                .param("templateCode", "MEMBER_IMPORT"))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_return400_when_categoryMissing_onInitiate() throws Exception {
    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateCode": "MEMBER_IMPORT",
                      "originalFilename": "x.xlsx",
                      "mimeType": "text/csv",
                      "sizeBytes": 100
                    }
                    """))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 5: Chạy test**

Run: `mvn test -Dtest=FileTemplateControllerTest`
Expected: PASS (5 test).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/platform/template/api/ \
        src/test/java/com/company/platform/template/api/FileTemplateControllerTest.java
git commit -m "Add FileTemplateController with initiate/confirm upload and download-url endpoints"
```

---

### Task 7: End-to-end integration test (Testcontainers, toàn bộ flow HTTP)

**Files:**
- Test: `src/test/java/com/company/platform/template/api/FileTemplateEndToEndIT.java`

**Interfaces:**
- Consumes: toàn bộ `FileTemplateController` (Task 6) qua HTTP thật (MockMvc) + MinIO thật cho PUT/GET nội dung file.

- [ ] **Step 1: Viết test**

`src/test/java/com/company/platform/template/api/FileTemplateEndToEndIT.java`:

```java
package com.company.platform.template.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end: ADMIN upload version 1 -> upload version 2 -> user thường (không ADMIN) download
 * được bản mới nhất (mặc định = version 2) và cả bản version=1 tường minh -> user thường bị từ
 * chối khi cố upload -> category/templateCode không tồn tại trả 404. Dùng Postgres + MinIO thật
 * (Testcontainers), không mock layer nào.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class FileTemplateEndToEndIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void minioProperties(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void should_uploadTwoVersions_and_downloadLatestOrSpecificVersion_and_denyNonAdminUpload()
      throws Exception {
    UUID adminId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String category = "MEMBER_" + suffix;
    String templateCode = "MEMBER_IMPORT_" + suffix;

    byte[] v1Content = "version-1-content".getBytes(StandardCharsets.UTF_8);
    byte[] v2Content = "version-2-content".getBytes(StandardCharsets.UTF_8);

    uploadTemplateVersion(adminId, category, templateCode, v1Content, "template v1");
    uploadTemplateVersion(adminId, category, templateCode, v2Content, "template v2");

    String latestUrl = downloadUrl(memberId, category, templateCode, null);
    assertThat(fetchContent(latestUrl)).isEqualTo(new String(v2Content, StandardCharsets.UTF_8));

    String v1Url = downloadUrl(memberId, category, templateCode, 1);
    assertThat(fetchContent(v1Url)).isEqualTo(new String(v1Content, StandardCharsets.UTF_8));

    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .header("X-User-Id", memberId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "category": "%s",
                      "templateCode": "%s",
                      "originalFilename": "hack.xlsx",
                      "mimeType": "text/csv",
                      "sizeBytes": 10
                    }
                    """
                        .formatted(category, templateCode)))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/templates/download-url")
                .header("X-User-Id", memberId.toString())
                .param("category", "KHONG_TON_TAI")
                .param("templateCode", "KHONG_TON_TAI"))
        .andExpect(status().isNotFound());
  }

  private void uploadTemplateVersion(
      UUID adminId, String category, String templateCode, byte[] content, String description)
      throws Exception {
    String initiateBody =
        mockMvc
            .perform(
                post("/api/templates/upload/initiate")
                    .header("X-User-Id", adminId.toString())
                    .header("X-User-Roles", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "category": "%s",
                          "templateCode": "%s",
                          "originalFilename": "template.csv",
                          "mimeType": "text/csv",
                          "sizeBytes": %d
                        }
                        """
                            .formatted(category, templateCode, content.length)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode initiateJson = objectMapper.readTree(initiateBody);
    String fileAssetId = initiateJson.get("fileAssetId").asText();
    String uploadUrl = initiateJson.get("uploadUrl").asText();

    HttpResponse<Void> putResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(uploadUrl))
                .PUT(BodyPublishers.ofByteArray(content))
                .build(),
            BodyHandlers.discarding());
    assertThat(putResponse.statusCode()).isEqualTo(200);

    mockMvc
        .perform(
            post("/api/templates/upload/confirm")
                .header("X-User-Id", adminId.toString())
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "fileAssetId": "%s",
                      "category": "%s",
                      "templateCode": "%s",
                      "checksumSha256": "fake-sha256",
                      "description": "%s"
                    }
                    """
                        .formatted(fileAssetId, category, templateCode, description)))
        .andExpect(status().isOk());
  }

  private String downloadUrl(UUID userId, String category, String templateCode, Integer version)
      throws Exception {
    MockHttpServletRequestBuilder requestBuilder =
        get("/api/templates/download-url")
            .header("X-User-Id", userId.toString())
            .param("category", category)
            .param("templateCode", templateCode);
    if (version != null) {
      requestBuilder = requestBuilder.param("version", version.toString());
    }
    String body =
        mockMvc
            .perform(requestBuilder)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).get("downloadUrl").asText();
  }

  private String fetchContent(String url) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }
}
```

- [ ] **Step 2: Chạy test**

Run: `mvn verify -Pintegration-test -Dit.test=FileTemplateEndToEndIT`
Expected: PASS (1 test, verify toàn bộ chuỗi HTTP + DB + MinIO).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/company/platform/template/api/FileTemplateEndToEndIT.java
git commit -m "Add end-to-end IT for file template upload/download flow"
```

---

### Task 8: Format + verify toàn bộ

**Files:** không tạo/sửa file mới — chỉ chạy lệnh verify.

- [ ] **Step 1: Format code**

Run: `mvn spotless:apply`
Expected: BUILD SUCCESS. Nếu có file bị format lại, commit riêng:

```bash
git add -A
git commit -m "Apply spotless formatting" --allow-empty
```

- [ ] **Step 2: Chạy full unit test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, tất cả test pass (bao gồm `FileTemplateServiceTest`, `FileTemplateControllerTest` mới thêm, không ảnh hưởng test cũ).

- [ ] **Step 3: Chạy full integration test suite (cần Docker)**

Run: `mvn verify -Pintegration-test`
Expected: BUILD SUCCESS, bao gồm `FileTemplateRepositoryIT`, `FileTemplateServiceIT`, `FileTemplateEndToEndIT` mới thêm.

- [ ] **Step 4: Chạy full build (đúng lệnh CI dùng)**

Run: `mvn clean install`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Báo cáo kết quả**

Không cần commit thêm ở bước này nếu Step 1 không có thay đổi — chỉ xác nhận trạng thái build sạch trước khi coi tính năng hoàn tất.
