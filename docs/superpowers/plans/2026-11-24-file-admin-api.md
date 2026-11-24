# File Admin API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thêm bộ API `/api/admin/**` (JWT + permission `file.*`) cho console quản trị file: cấu hình virus scan (tổng + theo category), danh sách/chi tiết/thống kê file, lịch sử upload/download, lịch sử thay đổi cấu hình.

**Architecture:** Code admin nằm trong module sở hữu dữ liệu (`file/api/admin`, `file/application/admin`, `shared/audit/api`), JWT chain riêng trong `shared/security` chỉ áp `/api/admin/**`; mọi path khác giữ cơ chế header hiện tại. Keyset pagination dùng chung qua `shared/pagination`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security OAuth2 resource server (ES256/JWKS), Spring Data JPA, Liquibase, PostgreSQL, JUnit 5 + Testcontainers + MockMvc.

**Spec:** `docs/superpowers/specs/2026-11-24-file-admin-api-design.md`

## Global Constraints

- Tên test: `should_<expected>_when_<condition>`; phần DB/transaction dùng Testcontainers `postgres:17-alpine` (`@ServiceConnection`), tên lớp `*IT`.
- Chạy unit: `mvn test`; IT: `mvn verify -Pintegration-test`; trước khi commit: `mvn spotless:apply`.
- DTO là `record`, không trả Entity; constructor injection; exception xử lý qua `@RestControllerAdvice`.
- Keyset pagination: `limit` mặc định 50, tối đa 200; `cursor` opaque (base64url của `createdAt|id`); response `{ items, nextCursor }`.
- Khoảng thời gian: `from`/`to` ISO-8601 instant; mặc định 30 ngày gần nhất; `to - from` > 92 ngày → 400.
- Thời gian lưu UTC: `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`; thống kê theo ngày dùng `app.admin.stats-zone` (mặc định `Asia/Ho_Chi_Minh`).
- Permission: `file.asset.view`, `file.history.view`, `file.config.view`, `file.config.manage`.
- Lỗi trả `ApiError(int status, String message, Instant timestamp)` như hiện có; 401/403 của admin chain cũng trả JSON cùng shape.
- Không trả `bucketName`/`objectKey` trong API file admin.
- Luồng upload/download **không đổi hành vi**; cờ scan chưa được đọc ở luồng upload.
- Link tải nhúng trong `GET /exports/{id}` (`findDownloadUrlByJobId`) **không** ghi audit download.
- Liquibase changeset mới đánh số `012`, `013`, `014`, author `platform`, thêm vào `db.changelog-master.yaml`.

## Review Focus

- Request không có JWT tới `/api/files/**`, `/api/internal/**`, `/exports`, `/imports`, `/api/templates`, actuator, swagger phải chạy như trước (header auth) — Task 1 regression test.
- Hai admin sửa cùng category/công tắc tổng với `version` cũ → người sau nhận 409, không ghi audit — Task 4.
- Cursor rác/bị sửa tay → 400, không 500 — Task 3 (`KeysetCursor`) + Task 5.
- Các dòng có cùng `created_at` nằm ở ranh giới trang → không lặp, không sót khi duyệt hết các trang — Task 5 & Task 7.
- Gọi `confirm` lại lần 2 (idempotent) không sinh thêm `FILE_UPLOAD_COMPLETED`; file chưa CLEAN không sinh `FILE_DOWNLOAD_URL_ISSUED` — Task 3.

---

### Task 1: JWT security chain cho `/api/admin/**`

**Files:**
- Modify: `pom.xml` — thêm `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server`, test: `spring-boot-starter-security-test` (tên artifact như `vsp-catalog-service/backend/pom.xml`).
- Create: `src/main/java/com/company/platform/shared/security/SecurityConfig.java`
- Create: `src/main/java/com/company/platform/shared/security/AdminSecurityProperties.java`
- Create: `src/main/java/com/company/platform/shared/security/FilePermissions.java`
- Create: `src/main/java/com/company/platform/shared/security/AdminActor.java`
- Modify: `src/main/resources/application.yml`
- Modify: các `@WebMvcTest` hiện có nếu bị vỡ (xem Step 5)
- Test: `src/test/java/com/company/platform/shared/security/AdminSecurityTest.java`, `src/test/java/com/company/platform/shared/security/AdminSecurityDisabledInProdTest.java`

**Interfaces:**
- Produces:
  - `FilePermissions.ASSET_VIEW = "file.asset.view"`, `HISTORY_VIEW = "file.history.view"`, `CONFIG_VIEW = "file.config.view"`, `CONFIG_MANAGE = "file.config.manage"`, `List<String> ALL`.
  - `@ConfigurationProperties("app.admin-security") record AdminSecurityProperties(boolean enabled, String issuer)` (issuer null → "").
  - `@Component AdminActor { UUID currentId(); }` — trả UUID từ `Jwt.getSubject()`; trả `null` khi security disabled (principal `local-dev`).
  - Bảng phân quyền (trong `SecurityConfig`, theo thứ tự match):
    - `GET /api/admin/files/*/history` → `HISTORY_VIEW`
    - `GET /api/admin/files`, `GET /api/admin/files/**` → `ASSET_VIEW`
    - `GET /api/admin/file-downloads/**`, `GET /api/admin/config-changes/**` (và path gốc) → `HISTORY_VIEW`
    - `GET /api/admin/virus-scan`, `GET /api/admin/file-categories/**` (và path gốc) → `CONFIG_VIEW`
    - `PUT /api/admin/virus-scan`, `PATCH /api/admin/file-categories/*` → `CONFIG_MANAGE`
    - còn lại dưới `/api/admin/**` → `denyAll`

- [ ] **Step 1: Viết test fail `AdminSecurityTest`**

`@WebMvcTest(controllers = AdminSecurityTest.PingController.class)` + `@Import(SecurityConfig.class)` + `@EnableConfigurationProperties(AdminSecurityProperties.class)`, property `app.admin-security.enabled=true`, `spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks`. `PingController` (static nested `@RestController` trong test) map `GET /api/admin/files`, `GET /api/admin/files/{id}/history`, `PUT /api/admin/virus-scan`, `GET /api/admin/unknown`, `GET /api/files/ping` trả 200 `"ok"` và với `/api/admin/files` trả `adminActor.currentId()`.
Test (dùng `SecurityMockMvcRequestPostProcessors.jwt()`):
- `should_return401_when_adminRequestHasNoToken`: `GET /api/admin/files` → 401, body JSON có `"status":401`.
- `should_return403_when_tokenLacksPermission`: jwt với `perms=[file.config.view]` → `GET /api/admin/files` 403, body `"status":403`.
- `should_return200_when_tokenHasAssetView`: jwt `sub=<uuid>`, `perms=[file.asset.view]` → 200, body = uuid đó.
- `should_requireHistoryView_when_readingFileHistory`: `perms=[file.asset.view]` → `/api/admin/files/{id}/history` 403; `perms=[file.history.view]` → 200.
- `should_requireConfigManage_when_updatingVirusScan`: `perms=[file.config.view]` → PUT 403; `[file.config.manage]` → 200.
- `should_denyAll_when_adminPathIsNotMapped`: `perms=FilePermissions.ALL` → `/api/admin/unknown` 403.
- `should_passThroughWithoutToken_when_pathIsNotAdmin`: `GET /api/files/ping` không token → 200; `POST` tới path không phải admin không bị CSRF chặn.
- `should_return401_when_subjectIsNotUuid`: cấu hình test `JwtDecoder` mock trả Jwt `sub="abc"` + gửi `Authorization: Bearer x` → 401.

Lưu ý: `jwt()` post-processor bỏ qua converter; test `sub` không phải UUID phải đi qua `JwtDecoder` bean mock (`@MockitoBean JwtDecoder`) để converter thật chạy. Các test khác có thể dùng `jwt().authorities(...)` + `jwt(j -> j.subject(...))`.

- [ ] **Step 2: Chạy, xác nhận fail**

Run: `mvn test -Dtest=AdminSecurityTest`
Expected: FAIL compile (`SecurityConfig` chưa có).

- [ ] **Step 3: Implement**

`SecurityConfig` (`@Configuration(proxyBeanMethods = false)`, `@EnableConfigurationProperties(AdminSecurityProperties.class)`):
- `@Bean @Order(1) SecurityFilterChain adminChain(...)`: `securityMatcher("/api/admin/**")`, csrf off, stateless, httpBasic/formLogin off, entry point 401 + access denied 403 ghi JSON `{"status":..,"message":..,"timestamp":..}` (dùng `ObjectMapper`/`JsonMapper` có sẵn của context), bảng phân quyền ở trên, `oauth2ResourceServer(jwt(converter))`.
- Khi `enabled=false`: nếu profile `prod` active → `IllegalStateException("app.admin-security.enabled=false is not allowed with profile 'prod'")`; ngược lại `anonymous(principal "local-dev", authorities = FilePermissions.ALL)`, permitAll trên admin chain, log WARN.
- `@Bean @Order(2) SecurityFilterChain defaultChain`: mọi request `permitAll`, csrf off, stateless, httpBasic/formLogin/logout off.
- `@Bean JwtDecoder`: copy catalog (`NimbusJwtDecoder.withJwkSetUri`, ES256, issuer tuỳ chọn).
- Converter: authorities = từng phần tử claim `perms`; nếu `sub` không parse được UUID → ném `org.springframework.security.oauth2.jwt.BadJwtException` (thành 401).
- `@Bean UserDetailsService` không khai báo; thêm `spring.autoconfigure.exclude` không cần — kiểm tra log không in "Using generated security password"; nếu có, khai báo `@Bean InMemoryUserDetailsManager` rỗng.

`application.yml`:
```yaml
spring:
  jpa:
    properties:
      hibernate.jdbc.time_zone: UTC
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${APP_ADMIN_JWKS_URI:http://localhost:8081/t/ticket-staff/.well-known/jwks.json}
app:
  admin-security:
    enabled: ${APP_ADMIN_SECURITY_ENABLED:true}
    issuer: ${APP_ADMIN_JWT_ISSUER:}
  admin:
    stats-zone: ${APP_ADMIN_STATS_ZONE:Asia/Ho_Chi_Minh}
```
(trộn vào khối `spring:`/`app:` hiện có, không tạo khối trùng).

- [ ] **Step 4: Chạy test mới, xác nhận pass**

Run: `mvn test -Dtest=AdminSecurityTest` → PASS.

- [ ] **Step 5: Test prod guard + regression toàn bộ unit test**

`AdminSecurityDisabledInProdTest`: `ApplicationContextRunner` (hoặc `WebApplicationContextRunner`) với `SecurityConfig`, profile `prod`, `app.admin-security.enabled=false` → `should_failStartup_when_securityDisabledInProd`: context fail, root cause message chứa `"not allowed with profile 'prod'"`.
Run: `mvn test` — toàn bộ. Các `@WebMvcTest` cũ (`FileUploadControllerTest`, `FileMultipartUploadControllerTest`, `FileInternalDownloadControllerTest`, `FileTemplateControllerTest`, `ExportControllerTest`) có thể nhận security mặc định của Boot (401/403 CSRF) vì slice không nạp `SecurityConfig`: thêm `SecurityConfig.class` vào `@Import` của từng test bị vỡ và property `spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks` (qua `@TestPropertySource` hoặc `src/test/resources/application.properties` nếu chưa có). Expected: tất cả PASS, không sửa assertion nào của test cũ.

- [ ] **Step 6: Regression IT**

Run: `mvn verify -Pintegration-test -Dit.test='AvatarUploadDownloadEndToEndIT,FileInternalDownloadEndToEndIT,FileTemplateEndToEndIT,ExportUserCsvEndToEndIT'` → PASS (các luồng header cũ không bị security chặn).

- [ ] **Step 7: Commit**

`mvn spotless:apply && git add -A && git commit -m "feat(security): JWT resource server chain for /api/admin/**"`

---

### Task 2: Schema — settings, category version, index admin

**Files:**
- Create: `src/main/resources/db/changelog/changes/012-init-file-platform-settings.yaml`
- Create: `src/main/resources/db/changelog/changes/013-file-categories-admin.yaml`
- Create: `src/main/resources/db/changelog/changes/014-admin-query-indexes.yaml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `src/main/java/com/company/platform/file/domain/FilePlatformSetting.java`, `FilePlatformSettingRepository.java`
- Modify: `src/main/java/com/company/platform/file/domain/FileCategory.java`
- Test: `src/test/java/com/company/platform/file/domain/AdminSchemaIT.java`

**Interfaces:**
- Produces:
  - Bảng `file_platform_settings(setting_key VARCHAR(100) PK, setting_value JSONB NOT NULL, updated_by UUID NULL, updated_at TIMESTAMP NOT NULL, version BIGINT NOT NULL DEFAULT 0)`; seed `('virus_scan.enabled', 'false'::jsonb, NULL, now(), 0)`.
  - `file_categories` thêm `version BIGINT NOT NULL DEFAULT 0`, `updated_by UUID NULL`. **Không** UPDATE `require_virus_scan`.
  - Index `idx_file_assets_created_id` trên `file_assets(created_at DESC, id DESC) WHERE deleted_at IS NULL` (dùng `sql` changeset vì partial index), `idx_audit_logs_type_action_created` trên `audit_logs(resource_type, action, created_at DESC, id DESC)`.
  - `@Entity FilePlatformSetting`: `String settingKey`, `@JdbcTypeCode(SqlTypes.JSON) JsonNode settingValue` (hoặc `Object`), `UUID updatedBy`, `Instant updatedAt`, `@Version long version`; method `void update(Object newValue, UUID actorId)`. Hằng `VIRUS_SCAN_ENABLED = "virus_scan.enabled"`.
  - `FilePlatformSettingRepository extends JpaRepository<FilePlatformSetting, String>`.
  - `FileCategory`: `@Version long version`, `UUID updatedBy`, getter; `void applyAdminUpdate(Boolean requireVirusScan, List<String> allowedMimeTypes, Long maxSizeBytes, Integer retentionDays, boolean clearRetention, UUID actorId)` — chỉ đổi field khác null (retention: `clearRetention=true` → null), set `updatedAt`, `updatedBy`.

- [ ] **Step 1: Viết `AdminSchemaIT`** (`@SpringBootTest(webEnvironment = NONE)`, Testcontainers):
- `should_seedVirusScanGlobalDisabled_when_migrated`: `findById("virus_scan.enabled")` có, giá trị boolean `false`, `version == 0`.
- `should_keepAllSeededCategoriesScanDisabled_when_migrated`: mọi `FileCategory.isRequireVirusScan() == false`, `getVersion() == 0`.
- `should_rejectStaleVersion_when_categorySavedTwiceConcurrently`: đọc cùng category ở 2 `TransactionTemplate` riêng, sửa và save lần lượt → lần 2 ném `ObjectOptimisticLockingFailureException`.
- `should_haveAdminIndexes_when_migrated`: query `pg_indexes` có `idx_file_assets_created_id` và `idx_audit_logs_type_action_created`.

- [ ] **Step 2:** Run `mvn verify -Pintegration-test -Dit.test=AdminSchemaIT` → FAIL.
- [ ] **Step 3:** Viết 3 changeset + entity/repository + sửa `FileCategory` như Interfaces.
- [ ] **Step 4:** Run lại → PASS; chạy thêm `-Dit.test='MultipartColumnsIT,FileUploadServiceIT'` → PASS.
- [ ] **Step 5: Commit** `feat(file): schema for admin settings, category versioning and admin indexes`

---

### Task 3: Audit metadata, resource id ổn định, cursor, và audit upload/download

**Files:**
- Modify: `src/main/java/com/company/platform/shared/audit/AuditLogService.java`
- Create: `src/main/java/com/company/platform/shared/audit/AuditResourceIds.java`
- Create: `src/main/java/com/company/platform/shared/audit/FileAuditActions.java`
- Create: `src/main/java/com/company/platform/shared/pagination/KeysetCursor.java`, `InvalidCursorException.java`, `PageSlice.java`, `TimeRange.java`, `InvalidTimeRangeException.java`
- Modify: `file/application/FileDownloadService.java`, `file/api/FileDownloadController.java`, `template/application/FileTemplateService.java`
- Modify: `file/application/FileAssetStatusFinalizer.java`, `file/application/FileUploadService.java`, `file/application/MultipartUploadService.java`, `file/application/CompleteMultipartUploadCommand.java`, `file/api/FileUploadController.java`, `file/api/FileMultipartUploadController.java`
- Test: `shared/audit/AuditResourceIdsTest.java`, `shared/pagination/KeysetCursorTest.java`, `shared/pagination/TimeRangeTest.java`, `file/application/FileAuditEventsIT.java`; cập nhật `FileDownloadServiceTest`, các controller test dùng signature cũ.

**Interfaces:**
- Produces:
  - `AuditLogService.record(UUID actorId, String action, String resourceType, UUID resourceId, Map<String,Object> metadata, AuditResult result)` (overload; bản cũ gọi sang với `null`).
  - `AuditResourceIds.forCategory(String code)` = `UUID.nameUUIDFromBytes(("FILE_CATEGORY:" + code).getBytes(UTF_8))`; `forSetting(String key)` = `... "SETTING:" + key ...`.
  - `FileAuditActions`: `UPLOAD_COMPLETED = "FILE_UPLOAD_COMPLETED"`, `DOWNLOAD_URL_ISSUED = "FILE_DOWNLOAD_URL_ISSUED"`, `DOWNLOADED_INTERNAL = "FILE_DOWNLOADED_INTERNAL"`, `VIRUS_SCAN_GLOBAL_UPDATED`, `FILE_CATEGORY_UPDATED`; resource types `FILE_ASSET`, `FILE_CATEGORY`, `FILE_PLATFORM_SETTING`.
  - `record KeysetCursor(Instant createdAt, UUID id)`: `String encode()` (base64url không padding của `createdAt.toEpochMilli()... ` — dùng `createdAt.toString() + "|" + id`), `static KeysetCursor decode(String)` → `InvalidCursorException` khi sai; `static KeysetCursor decodeOrNull(String)` (null/blank → null).
  - `record PageSlice<T>(List<T> items, String nextCursor)`.
  - `record TimeRange(Instant from, Instant to)`: `static TimeRange resolve(Instant from, Instant to, Clock clock)` — thiếu `to` → now; thiếu `from` → `to - 30d`; `from >= to` hoặc `to - from > 92d` → `InvalidTimeRangeException`. Hằng `DEFAULT_DAYS = 30`, `MAX_DAYS = 92`.
  - `static int PageSlice.clampLimit(Integer limit)`: null → 50, <1 → 1, >200 → 200.
  - `FileDownloadService.generateDownloadUrl(UUID fileAssetId, UUID actorId, String via)` — sau khi sinh URL thành công ghi `DOWNLOAD_URL_ISSUED`, metadata `{expiresInSeconds, via}`; `via` ∈ `"FILE_API"`, `"TEMPLATE_API"`. `findDownloadUrlByJobId` **không** đổi, không ghi.
  - `FileAssetStatusFinalizer.finalizeToClean(UUID fileAssetId, String checksumSha256, UUID actorId)` — ghi `UPLOAD_COMPLETED` (metadata `{sizeBytes, mimeType, categoryCode, source:"USER_UPLOAD"}`) chỉ khi CAS `UPLOADED_DMZ→CLEAN` thành công; nhánh đã CLEAN trả về sớm không ghi.
  - `FileUploadService.confirmUpload(UUID fileAssetId, String checksumSha256, UUID actorId)`; `registerServerGeneratedFile` ghi `UPLOAD_COMPLETED` actor `null`, `source:"JOB_GENERATED"`.
  - `CompleteMultipartUploadCommand` thêm field `UUID actorId` (cuối); controller truyền `userContext.userId()`.

- [ ] **Step 1: Unit test fail**
- `AuditResourceIdsTest.should_returnSameUuid_when_sameCategoryCode` và `should_differ_when_categoryVsSettingSameName` (`forCategory("X") != forSetting("X")`).
- `KeysetCursorTest`: `should_roundTrip_when_encodedThenDecoded` (giữ nguyên nano của Instant); `should_throwInvalidCursor_when_garbage` cho `"!!!"`, `"YWJj"` (base64 của "abc"), `base64("2026-01-01T00:00:00Z|not-a-uuid")`.
- `TimeRangeTest` với `Clock.fixed(2026-11-24T00:00:00Z)`: mặc định → `[2026-10-25T00:00Z, 2026-11-24T00:00Z]`; 92 ngày đúng → ok; 92 ngày + 1s → `InvalidTimeRangeException`; `from == to` → exception.
- `PageSlice.clampLimit`: null→50, 0→1, 500→200.

- [ ] **Step 2:** `mvn test -Dtest='AuditResourceIdsTest,KeysetCursorTest,TimeRangeTest'` → FAIL; implement; → PASS.

- [ ] **Step 3: IT fail `FileAuditEventsIT`** (`@SpringBootTest(NONE)` + Postgres + MinIO container theo mẫu `FileUploadServiceIT`):
- `should_recordUploadCompletedOnce_when_confirmCalledTwice`: initiate + PUT object + `confirmUpload(id, sha, actor)` 2 lần → đúng 1 audit `FILE_UPLOAD_COMPLETED`, `actorId == actor`, metadata `sizeBytes`/`mimeType`/`categoryCode`/`source=USER_UPLOAD`.
- `should_recordUploadCompletedWithNullActor_when_serverGeneratedFile`: `registerServerGeneratedFile` → 1 audit, `actorId == null`, `source=JOB_GENERATED`.
- `should_recordDownloadUrlIssued_when_fileIsClean`: → 1 audit `FILE_DOWNLOAD_URL_ISSUED`, metadata `via=FILE_API`.
- `should_notRecordDownloadUrlIssued_when_fileNotClean`: file UPLOADING → `FileNotAvailableForDownloadException`, 0 audit loại đó.
- `should_notRecordDownloadUrlIssued_when_urlEmbeddedInExportStatus`: `findDownloadUrlByJobId` trên file CLEAN có `jobId` → 0 audit loại đó.

- [ ] **Step 4:** Implement thay đổi signature + call site (`FileDownloadController` truyền `userContext.userId()` — inject `UserContext` như `FileUploadController`; `FileTemplateService` truyền `user.userId(), "TEMPLATE_API"`). Cập nhật test cũ theo signature mới (chỉ đổi tham số, không đổi assertion).
- [ ] **Step 5:** `mvn test` → PASS; `mvn verify -Pintegration-test -Dit.test='FileAuditEventsIT,AvatarUploadDownloadEndToEndIT,MultipartUploadEndToEndIT,FileTemplateEndToEndIT,ExportUserCsvEndToEndIT'` → PASS.
- [ ] **Step 6: Commit** `feat(audit): explicit upload/download audit events with metadata`

---

### Task 4: Cấu hình virus scan & category (admin)

**Files:**
- Create: `file/application/admin/VirusScanSettingsService.java`, `FileCategoryAdminService.java`, `UpdateFileCategoryCommand.java`, `VirusScanOverview.java`, `CategoryScanState.java`
- Create: `file/domain/StaleVersionException.java`
- Create: `file/api/admin/FileConfigAdminController.java`, `VirusScanResponse.java`, `UpdateVirusScanRequest.java`, `FileCategoryAdminResponse.java`, `UpdateFileCategoryRequest.java`
- Modify: `file/api/FileApiExceptionHandler.java` (thêm handler `StaleVersionException`→409, `ObjectOptimisticLockingFailureException`→409, `InvalidCursorException`/`InvalidTimeRangeException`→400, `MethodArgumentNotValidException`→400 nếu chưa có)
- Test: `file/application/admin/FileCategoryAdminServiceTest.java`, `file/application/admin/FileConfigAdminIT.java`, `file/api/admin/FileConfigAdminControllerTest.java`

**Interfaces:**
- Consumes: `FilePlatformSetting`, `FileCategory.applyAdminUpdate`, `AuditLogService.record(... metadata ...)`, `AuditResourceIds`, `FileAuditActions`, `AdminActor`.
- Produces:
  - `VirusScanSettingsService.boolean isGloballyEnabled()`; `VirusScanOverview overview()`; `VirusScanOverview updateGlobal(boolean enabled, long expectedVersion, UUID actorId)` (`@Transactional`; version lệch → `StaleVersionException`; giá trị không đổi → trả overview, không ghi audit; đổi → audit `VIRUS_SCAN_GLOBAL_UPDATED`, resource `FILE_PLATFORM_SETTING`/`forSetting("virus_scan.enabled")`, metadata `{resourceKey:"virus_scan.enabled", old, new}` cùng transaction).
  - `record VirusScanOverview(boolean globalEnabled, long version, UUID updatedBy, Instant updatedAt, boolean engineIntegrated /* luôn false */, List<CategoryScanState> categories)`; `record CategoryScanState(String code, boolean requireVirusScan, boolean effective)`, `effective = globalEnabled && requireVirusScan`.
  - `FileCategoryAdminService.List<FileCategory> list()` (sort theo code), `FileCategory get(String code)`, `FileCategory update(String code, UpdateFileCategoryCommand cmd, UUID actorId)`; `record UpdateFileCategoryCommand(long version, Boolean requireVirusScan, List<String> allowedMimeTypes, Long maxSizeBytes, Integer retentionDays, boolean retentionDaysPresent)`.
  - Validate (→ `FileValidationException` 400): `allowedMimeTypes` khác null thì không rỗng và mỗi phần tử khớp `^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$` (không `*`); `maxSizeBytes` khác null thì > 0; `retentionDays` khác null thì > 0. Không có thay đổi thực sự → không ghi audit. Có thay đổi → audit `FILE_CATEGORY_UPDATED`, metadata `{resourceKey: code, changes: {field: {old, new}}}`.
  - Endpoint: `GET/PUT /api/admin/virus-scan`, `GET /api/admin/file-categories`, `GET /api/admin/file-categories/{code}`, `PATCH /api/admin/file-categories/{code}`. PATCH body `retentionDays: null` tường minh = xoá retention (dùng `JsonNode`/`JsonNullable`-style: đọc request thành `Map` hoặc `JsonNode` để phân biệt vắng mặt vs null). `FileCategoryAdminResponse` có `effectiveVirusScan`.

- [ ] **Step 1: Unit test `FileCategoryAdminServiceTest`** (Mockito): `should_rejectWildcardMime_when_updating` (`"image/*"` → `FileValidationException`), `should_rejectEmptyMimeList`, `should_rejectNonPositiveMaxSize`, `should_throwStaleVersion_when_versionMismatch`, `should_notAudit_when_noFieldChanges`, `should_auditChangesOnly_when_someFieldsChange` (metadata `changes` chỉ chứa field thật sự đổi).
- [ ] **Step 2:** Chạy → FAIL; implement service; → PASS.
- [ ] **Step 3: IT `FileConfigAdminIT`**: `should_toggleGlobal_and_audit_when_versionMatches`; `should_return409AndNotAudit_when_staleVersion`; `should_computeEffective_when_globalOffAndCategoryOn` (global false + category true → effective false; global true → true); `should_rollbackAudit_when_updateFails` (ép lỗi sau khi audit — ví dụ `@MockitoSpyBean FilePlatformSettingRepository` ném khi `saveAndFlush` → 0 dòng audit).
- [ ] **Step 4: Controller test `FileConfigAdminControllerTest`** (`@WebMvcTest` + `@Import(SecurityConfig.class)` + `jwt()` với quyền đủ): PUT với `version` lệch → 409 JSON `status=409`; PATCH `allowedMimeTypes:["image/*"]` → 400; PATCH `{"version":0,"retentionDays":null}` → service nhận `retentionDaysPresent=true, retentionDays=null`; PATCH thiếu `version` → 400.
- [ ] **Step 5:** Chạy unit + IT → PASS.
- [ ] **Step 6: Commit** `feat(file): admin virus-scan and category configuration API`

---

### Task 5: Danh sách & chi tiết file (lịch sử upload)

**Files:**
- Create: `file/application/admin/FileAdminQueryService.java`, `FileSearchCriteria.java`, `FileAdminView.java`
- Create: `file/domain/FileAssetAdminQueryRepository.java` (custom, dùng `NamedParameterJdbcTemplate` hoặc `EntityManager` native SQL)
- Create: `file/api/admin/FileAdminController.java`, `FileAdminItemResponse.java`, `FileAdminDetailResponse.java`, `FileAdminPageResponse.java`
- Test: `file/application/admin/FileAdminQueryIT.java`, `file/api/admin/FileAdminControllerTest.java`

**Interfaces:**
- Consumes: `KeysetCursor`, `PageSlice`, `TimeRange`, `VirusScanSettingsService.isGloballyEnabled()`.
- Produces:
  - `record FileSearchCriteria(TimeRange range, String categoryCode, Set<StorageStatus> storageStatuses, ScanStatus scanStatus, String mimeType, UUID uploadedBy, String ownerType, UUID ownerId, String filename, FileSource source, boolean includeDeleted)`; `enum FileSource { USER_UPLOAD, JOB_GENERATED }`.
  - `FileAdminQueryService.PageSlice<FileAdminView> search(FileSearchCriteria c, KeysetCursor cursor, int limit)`; `FileAdminView get(UUID id)` (thấy cả file soft-delete; không có → `FileAssetNotFoundException`); `boolean effectiveVirusScan(String categoryCode)`.
  - SQL: `WHERE created_at >= :from AND created_at < :to` + filter động + `(created_at, id) < (:cAt, :cId)` khi có cursor + `ORDER BY created_at DESC, id DESC LIMIT :limit+1` (lấy dư 1 dòng để biết có trang sau). `mimeType` kết thúc bằng `/` → `LIKE :prefix || '%'`, ngược lại so bằng. `filename` → `original_filename ILIKE '%' || :q || '%'` (escape `%`, `_`, `\`). Mặc định `deleted_at IS NULL`.
  - `FileAdminView`: `id, originalFilename, mimeType, sizeBytes, categoryCode, storageStatus, scanStatus, ownerType, ownerId, uploadedBy, jobId, checksumSha256, createdAt, updatedAt, expiresAt, deletedAt` (không bucket/objectKey).
  - `GET /api/admin/files` (query params như spec 6.1, `storageStatus` lặp được), `GET /api/admin/files/{id}` (thêm `effectiveVirusScan`).

- [ ] **Step 1: IT `FileAdminQueryIT`** (seed bằng `JdbcTemplate` insert thẳng `file_assets` với `created_at` kiểm soát):
- `should_pageWithoutDuplicatesOrGaps_when_rowsShareCreatedAt`: 7 dòng, 3 dòng trùng `created_at`; duyệt `limit=2` tới hết → đúng 7 id, không trùng, đúng thứ tự `created_at DESC, id DESC`, trang cuối `nextCursor == null`.
- `should_filterByMimePrefix_when_mimeEndsWithSlash` (`image/` khớp `image/png`, `image/jpeg`, không khớp `application/pdf`).
- `should_excludeSoftDeleted_byDefault` và `should_includeSoftDeleted_when_flagSet`.
- `should_filterBySource` (`JOB_GENERATED` chỉ ra dòng có `job_id`).
- `should_treatWildcardCharsLiterally_when_filenameContainsPercent` (`filename="50%"` chỉ khớp tên chứa "50%").
- `should_returnDeletedFile_when_getById`.
- [ ] **Step 2:** → FAIL; implement repository + service; → PASS.
- [ ] **Step 3: Controller test**: `cursor=garbage` → 400; range 93 ngày → 400; `limit=1000` → service nhận 200; response không có key `bucketName`/`objectKey`; `GET /{id}` không tồn tại → 404.
- [ ] **Step 4:** chạy → PASS.
- [ ] **Step 5: Commit** `feat(file): admin file search and detail API`

---

### Task 6: Thống kê file

**Files:**
- Create: `file/application/admin/FileStatsService.java`, `FileStats.java`
- Modify: `file/domain/FileAssetAdminQueryRepository.java`, `file/api/admin/FileAdminController.java`, thêm `FileStatsResponse.java`
- Create: `shared/config/AdminProperties.java` (`@ConfigurationProperties("app.admin") record AdminProperties(ZoneId statsZone)`, mặc định `Asia/Ho_Chi_Minh`)
- Test: `file/application/admin/FileStatsIT.java`

**Interfaces:**
- Produces: `FileStats FileStatsService.stats(TimeRange range, String categoryCode)`; `record FileStats(long totalFiles, long totalBytes, List<Bucket> byStorageStatus, List<Bucket> byCategory, List<Bucket> byMimeType, List<DailyBucket> daily)`, `record Bucket(String key, long files, long bytes)`, `record DailyBucket(LocalDate date, long files, long bytes)`. `GET /api/admin/files/stats?from&to&categoryCode`.
- Quy tắc: chỉ `deleted_at IS NULL`; `byMimeType` top 10 theo `files` (hoà thì theo key), phần còn lại gộp `"OTHER"` (chỉ thêm khi có); `daily` group theo `(created_at AT TIME ZONE 'UTC' AT TIME ZONE :zone)::date`, chỉ ngày có dữ liệu, sắp tăng dần.

- [ ] **Step 1: IT `FileStatsIT`**: `should_bucketDailyInVietnamTime_when_fileCreatedAt17hUtc` (file `2026-11-23T17:30:00Z` → `daily` ngày `2026-11-24`; file `2026-11-23T16:59:00Z` → `2026-11-23`); `should_groupBeyondTop10AsOther` (12 mime khác nhau → 11 bucket, bucket `OTHER` có tổng đúng); `should_excludeSoftDeleted`; `should_sumBytesAndCounts`.
- [ ] **Step 2:** → FAIL; implement; → PASS.
- [ ] **Step 3: Commit** `feat(file): admin file statistics API`

---

### Task 7: Lịch sử download, lịch sử file, lịch sử thay đổi cấu hình

**Files:**
- Create: `shared/audit/AuditAdminQueryRepository.java`, `shared/audit/FileAssetSummaryLookup.java`, `shared/audit/FileAssetSummary.java`
- Create: `shared/audit/api/AuditAdminController.java`, `AuditAdminExceptionHandler.java`, `FileDownloadHistoryItemResponse.java`, `AuditEntryResponse.java`, `ConfigChangeItemResponse.java`, page response records
- Create: `file/api/FileAssetSummaryLookupAdapter.java` (implement `FileAssetSummaryLookup`)
- Ghi chú: `GET /api/admin/files/{id}/history` đặt trong `AuditAdminController`, không sửa `FileAdminController`.
- Test: `shared/audit/AuditAdminQueryIT.java`, `shared/audit/api/AuditAdminControllerTest.java`

**Interfaces:**
- Consumes: `KeysetCursor`, `PageSlice`, `TimeRange`, `FileAuditActions`.
- Produces:
  - `interface FileAssetSummaryLookup { Map<UUID, FileAssetSummary> findSummaries(Collection<UUID> ids); }`; `record FileAssetSummary(UUID id, String originalFilename, String mimeType, long sizeBytes, String categoryCode)`. Adapter: 1 query `WHERE id IN (:ids)` (kể cả đã soft-delete).
  - `AuditAdminQueryRepository`:
    - `PageSlice<AuditLog> downloads(TimeRange r, UUID actorId, UUID fileAssetId, AuditResult result, DownloadChannel channel, KeysetCursor c, int limit)` — `resource_type='FILE_ASSET' AND (action IN ('FILE_DOWNLOAD_URL_ISSUED','FILE_DOWNLOADED_INTERNAL') OR (action='READ' AND result='DENIED'))`; `channel=PRESIGNED` → `FILE_DOWNLOAD_URL_ISSUED` + `READ DENIED`, `INTERNAL` → `FILE_DOWNLOADED_INTERNAL`.
    - `PageSlice<AuditLog> forFile(UUID fileAssetId, KeysetCursor c, int limit)` — mọi action `resource_type='FILE_ASSET' AND resource_id=:id`, không giới hạn range.
    - `PageSlice<AuditLog> configChanges(TimeRange r, UUID actorId, String resourceKey, KeysetCursor c, int limit)` — action `IN ('VIRUS_SCAN_GLOBAL_UPDATED','FILE_CATEGORY_UPDATED')`, `resourceKey` lọc theo `metadata->>'resourceKey'`.
    - Order `created_at DESC, id DESC`, `LIMIT limit+1`.
  - `enum DownloadChannel { PRESIGNED, INTERNAL }`; item `channel` suy từ action (`FILE_DOWNLOADED_INTERNAL` → INTERNAL, còn lại PRESIGNED).
  - Endpoint: `GET /api/admin/file-downloads`, `GET /api/admin/files/{id}/history`, `GET /api/admin/config-changes`. Swagger description của `/file-downloads` ghi: "Không gồm file kết quả export (link tải nhúng trong GET /exports/{id}); dữ liệu trước khi deploy chỉ có READ, xem /files/{id}/history."
  - `AuditAdminExceptionHandler` (`basePackages = "com.company.platform.shared.audit.api"`): `InvalidCursorException`, `InvalidTimeRangeException`, `MethodArgumentTypeMismatchException` → 400 `ApiError`.

- [ ] **Step 1: IT `AuditAdminQueryIT`** (seed `audit_logs` bằng JdbcTemplate + vài `file_assets`):
- `should_listOnlyDownloadActionsAndDeniedReads` (READ SUCCESS và WRITE không xuất hiện).
- `should_filterByChannel`.
- `should_pageWithoutDuplicates_when_rowsShareCreatedAt`.
- `should_enrichWithFileSummary_including_softDeletedFile` (qua controller/service gọi lookup).
- `should_filterConfigChangesByResourceKey`.
- `should_returnAllActionsForFile_when_readingFileHistory`.
- [ ] **Step 2:** → FAIL; implement; → PASS.
- [ ] **Step 3: Controller test**: cursor rác → 400; `channel=FOO` → 400; item có `originalFilename` khi lookup trả về, `null` khi file không còn.
- [ ] **Step 4: Commit** `feat(audit): admin download, file and config-change history API`

---

### Task 8: Seed permission bên identity

**Files:**
- Create: `/Users/tigerpro/Documents/SA/vinspeed/vsp-identity-service/src/main/resources/db/migration/identity/V202611241100__identity_seed_file_app.sql`
- Test: chạy test migration sẵn có của identity (`ApplicationMigrationIntegrationTest`) + thêm 1 test method.

**Interfaces:**
- Produces: permission `file.asset.view` ("Xem danh sách, chi tiết, thống kê file"), `file.history.view` ("Xem lịch sử upload/download và lịch sử thay đổi cấu hình file"), `file.config.view` ("Xem cấu hình loại file và virus scan"), `file.config.manage` ("Sửa cấu hình loại file, bật/tắt virus scan"); role tenant `ticket-staff`: `FILE_ADMIN` ("Quản trị file", `is_sensitive=true`, cả 4 quyền), `FILE_VIEWER` ("Xem thông tin file", false, 3 quyền `*.view`). Application `file` đã có — **không** INSERT `identity.application`. Khuôn SQL theo `V202611231000__identity_seed_catalog_app.sql`.

- [ ] **Step 1:** Trong `vsp-identity-service/src/test/java/vn/hsr/ticket/identity/rbac/infrastructure/ApplicationMigrationIntegrationTest.java` thêm `seed_role_file_dung_ma_tran` theo mẫu `seed_role_catalog_dung_ma_tran`: `FILE_ADMIN` có đúng 4 quyền, `FILE_VIEWER` đúng 3 quyền view, cả hai gắn application `file`.
- [ ] **Step 2:** Chạy test đó trong identity (lệnh test của repo identity, ví dụ `mvn test -Dtest=ApplicationMigrationIntegrationTest`) → FAIL; viết migration; → PASS.
- [ ] **Step 3: Commit** (repo identity, nhánh riêng) `feat(identity): seed file application permissions and roles`

---

### Task 9: Kiểm tra cuối & tài liệu

- [ ] **Step 1:** `mvn spotless:apply && mvn clean verify -Pintegration-test` (file-platform) → BUILD SUCCESS.
- [ ] **Step 2:** Chạy app local với `APP_ADMIN_SECURITY_ENABLED=false` (profile dev), mở `/v3/api-docs`, xác nhận có đủ các endpoint admin sau: `GET /api/admin/files`, `GET /api/admin/files/{id}`, `GET /api/admin/files/{id}/history`, `GET /api/admin/files/stats`, `GET /api/admin/file-downloads`, `GET /api/admin/config-changes`, `GET|PUT /api/admin/virus-scan`, `GET /api/admin/file-categories`, `GET|PATCH /api/admin/file-categories/{code}`. Lưu `/v3/api-docs` ra `docs/openapi/file-admin.json` cho đợt frontend.
- [ ] **Step 3:** Cập nhật `CLAUDE.md` (mục cấu trúc thư mục: `shared/security`, `shared/pagination`, `file/api/admin`) và đổi `Status` của spec thành `Approved for implementation`.
- [ ] **Step 4: Commit** `docs: file admin API openapi snapshot and structure notes`
