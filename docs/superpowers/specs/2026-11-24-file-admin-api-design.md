# Design Doc: File Admin API (console quản trị file)

Status: Approved for implementation (2026-11-24)
Date: 2026-11-24

## 1. Mục tiêu & lý do

Platform Console (`vsp-frontend`) đã có module quản trị cho Catalog và Notification; File
Platform chưa có. Đợt này làm **backend** cho module "File" của console, frontend làm sau
(đợt riêng, dựa trên OpenAPI sinh từ đợt này).

Admin cần:
1. Bật/tắt **virus scan** theo từng file category + **công tắc tổng** toàn hệ thống. Mặc
   định **tắt**. Đợt này chỉ là cấu hình lưu DB — tích hợp ClamAV thật để đợt sau.
2. Xem **thông tin file đã upload**: tên, kiểu file (mime), dung lượng, thời gian, trạng thái,
   category, người upload.
3. Xem **lịch sử upload / download**.
4. (Đề xuất, đã đồng ý) **Thống kê tổng quan** cho dashboard và **lịch sử thay đổi cấu hình**.

### Non-goals
- Không tích hợp ClamAV, không thêm luồng `SCANNING`/`QUARANTINED` thật. Luồng upload hiện
  tại (UPLOADING → UPLOADED_DMZ → CLEAN) **không đổi**.
- Admin **chỉ xem**: không xoá file, không tải file hộ user, không tạo/xoá category.
- Không đổi cơ chế auth của các API end-user hiện có (`X-User-Id`/`X-User-Roles`) và
  internal API (`X-Internal-Api-Key`).
- Không làm frontend.

### Anti-goals (làm thế này là hỏng dù "chạy được")
- Bật cờ scan làm thay đổi hành vi upload/download (VD chặn download, đẩy file sang trạng thái
  chờ scan không bao giờ xong) — vì chưa có engine scan.
- API admin lọt ra ngoài không cần JWT, hoặc thêm Spring Security làm vỡ các API đang chạy
  bằng header.
- Query admin dùng OFFSET lớn / load full bảng `file_assets` hoặc `audit_logs`.

## 2. Ràng buộc (từ CLAUDE.md & code hiện có)

- Authorization check ở tầng API.
- Module chỉ gọi vào package `api` của module khác — query đọc `file_assets` nằm trong module
  `file`, query đọc `audit_logs` nằm trong `shared/audit`.
- Keyset pagination cho dữ liệu lớn (`WHERE (created_at, id) < (:c, :id)` …), không OFFSET.
- Schema đổi qua Liquibase (`db/changelog/changes/0xx-*.yaml`), `ddl-auto: none`.
- Spring Boot 4.1, chưa có Spring Security trong `pom.xml`.
- JWT do `vsp-identity-service` cấp (ES256, JWKS), `sub` = user UUID, claim `perms` là danh
  sách permission code — giống `vsp-catalog-service`.

### Thời gian & múi giờ
- Lưu và so sánh thời gian theo **UTC**. Thêm `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`
  vào `application.yml`, giống catalog và notification, để giá trị ghi vào cột `TIMESTAMP` (không
  lưu múi giờ) không còn phụ thuộc `TZ` của JVM.
- **Giả định cần xác nhận trước khi deploy:** production của file-platform đang chạy `TZ=UTC`
  (giống `devops/compose.local.yaml`). Nếu không đúng, dữ liệu cũ đã ghi theo giờ local và phải có
  migration chuyển đổi đi kèm.
- Giờ Việt Nam chỉ dùng khi gom thống kê theo ngày (`app.admin.stats-zone`, mặc định
  `Asia/Ho_Chi_Minh`; UTC+7 cố định, không có DST nên không có gap/overlap). API trả ISO-8601,
  frontend tự hiển thị theo giờ VN.
- Đổi cột sang `timestamptz` cho đồng bộ với các service khác: **để đợt sau**.

## 3. Approaches đã cân nhắc

| # | Cách | Đánh đổi | Quyết định |
|---|------|----------|-----------|
| 1 | Admin code nằm trong module sở hữu dữ liệu: `file/api/admin`, `shared/audit`, + `shared/security` cho JWT chain chỉ áp `/api/admin/**` | Đúng quy tắc module boundary, không phải mở domain/repository cho module khác; endpoint admin rải 2–3 package | **Chọn** |
| 2 | Module `admin/` riêng gom toàn bộ endpoint | Dễ tìm, giống "console API" của notification; nhưng phải đọc dữ liệu của file + audit → hoặc mở thêm public interface cho từng module, hoặc phá quy tắc | Loại |
| 3 | Service admin riêng dùng chung DB | Ngược thiết kế modular monolith | Loại |

Auth: chọn **JWT resource server + permission `file.*`** (giống catalog), thay vì giữ header +
role `ADMIN` — để console gọi thẳng với SSO token và phân quyền chi tiết được.

## 4. Bảo mật

### 4.1 Dependency
Thêm `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`.

### 4.2 Hai SecurityFilterChain (`shared/security/SecurityConfig`)
1. **Admin chain** — `securityMatcher("/api/admin/**")`, `@Order(1)`: stateless, CSRF off,
   `oauth2ResourceServer().jwt()`, authorities = từng phần tử claim `perms` (as-is) — copy
   `jwtAuthenticationConverter` của catalog. Phân quyền theo path + method (mục 4.3).
   401/403 trả `ApiError` cùng format hiện có của service.
2. **Default chain** — mọi path còn lại, `@Order(2)`: `permitAll`, CSRF off, stateless, tắt
   httpBasic/formLogin. Giữ nguyên hành vi hiện tại: `UserContext` (header) +
   `@RequiresAccess` + `InternalApiKeyInterceptor` tiếp tục làm việc của chúng.

Cấu hình (`app.admin-security.*`):
- `enabled` (mặc định `true`). `false` = local dev: admin chain cấp mọi quyền `file.*` cho
  principal `local-dev` (giống `LOCAL_ADMIN` của catalog); **fail fast nếu profile `prod`**.
- `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` ← `APP_ADMIN_JWKS_URI`.
- `issuer` (tuỳ chọn) ← `APP_ADMIN_JWT_ISSUER`; rỗng = chỉ verify chữ ký + hạn.

Admin endpoints **không** dùng `UserContext` (header). Actor id cho audit lấy từ JWT `sub`
(UUID) qua một helper `AdminActor.currentId()` trong `shared/security`; `sub` không phải
UUID → 401.

### 4.3 Permission (identity application `file`)

| Permission | Cho phép |
|---|---|
| `file.asset.view` | Danh sách/chi tiết file, thống kê |
| `file.history.view` | Lịch sử upload/download, lịch sử thay đổi cấu hình |
| `file.config.view` | Xem category + cấu hình virus scan |
| `file.config.manage` | Sửa category, bật/tắt scan (category & tổng) |

Role seed (tenant `ticket-staff`): `FILE_ADMIN` (tất cả, `is_sensitive=true`),
`FILE_VIEWER` (3 quyền `*.view`).
Migration mới trong `vsp-identity-service`:
`db/migration/identity/V202611241100__identity_seed_file_app.sql` (`V202611241000` đã bị
`identity_seed_job_app` dùng), cùng khuôn với `V202611231000__identity_seed_catalog_app.sql`.
Application `file` đã được seed sẵn trong `V202611041000__identity_application.sql`, nên migration
này chỉ thêm permission, role và role_permission (`ON CONFLICT DO NOTHING`).

## 5. Dữ liệu (Liquibase)

### 5.1 `012-init-file-platform-settings.yaml`
Bảng `file_platform_settings`:
- `setting_key VARCHAR(100) PK`, `setting_value JSONB NOT NULL`, `updated_by UUID NULL`,
  `updated_at TIMESTAMP NOT NULL`, `version BIGINT NOT NULL DEFAULT 0` (optimistic lock).
- Seed: `virus_scan.enabled = false`.

Domain: `file/domain/FilePlatformSetting` + repository; service đọc/ghi qua
`file/application/VirusScanSettingsService`.

### 5.2 `013-file-categories-admin.yaml`
- `require_virus_scan` đã có default `false`, và cả 4 category seed (006, 007, 008, 010) đều
  đặt `false`, nên **không** cần `UPDATE`. Cố tình không reset, để không ghi đè category nào đã
  được bật tay. *(Quyết định của Claude.)*
- Thêm cột `version BIGINT NOT NULL DEFAULT 0` (optimistic lock cho sửa category),
  `updated_by UUID NULL`.

### 5.3 `014-admin-query-indexes.yaml`
- `file_assets (created_at DESC, id DESC) WHERE deleted_at IS NULL` — keyset cho danh sách file.
- `audit_logs (resource_type, action, created_at DESC, id DESC)` — keyset cho lịch sử
  download và lịch sử cấu hình.

### 5.4 Audit events mới (ghi vào `audit_logs` có sẵn)

Hiện trạng: `ResourceAccessAuditAspect` ghi `READ`/`WRITE` cho mọi lần check quyền, kể cả
khi sau đó download fail vì file chưa CLEAN — nên `READ SUCCESS` **không** có nghĩa là đã cấp
URL. Upload hiện không có event riêng.

Thêm các action tường minh:

| action | resource_type | Khi nào | metadata |
|---|---|---|---|
| `FILE_DOWNLOAD_URL_ISSUED` | `FILE_ASSET` | URL tải được cấp thành công qua `GET /api/files/{id}/download-url` hoặc `GET /api/templates/download-url`. Actor = user trong `UserContext`: controller truyền `actorId` vào `FileDownloadService.generateDownloadUrl(fileAssetId, actorId)` (đổi signature). Link tải nhúng trong `GET /exports/{id}` (`findDownloadUrlByJobId`) **không** ghi: export đã có audit trail riêng ở phía service (quyết định của người dùng, phương án a) | `{expiresInSeconds, via: FILE_API\|TEMPLATE_API}` |
| `FILE_UPLOAD_COMPLETED` | `FILE_ASSET` | File chuyển sang CLEAN: (a) `FileAssetStatusFinalizer` (single + multipart), actor = user trong `UserContext` của request confirm/complete; (b) `FileUploadService.registerServerGeneratedFile` (file do job sinh), actor = `null`. **Không** ghi khi gọi lại idempotent lúc đã CLEAN *(Quyết định của Claude.)* | `{sizeBytes, mimeType, categoryCode, source: USER_UPLOAD\|JOB_GENERATED}` |
| `VIRUS_SCAN_GLOBAL_UPDATED` | `FILE_PLATFORM_SETTING` | Đổi công tắc tổng | `{old, new}` |
| `FILE_CATEGORY_UPDATED` | `FILE_CATEGORY` | Sửa category | `{changes: {field: {old, new}}}` |

`FILE_DOWNLOADED_INTERNAL` đã có sẵn, giữ nguyên.

Vấn đề kỹ thuật: `audit_logs.resource_id` là `UUID NOT NULL`, còn category dùng `code`
(string) và setting dùng `setting_key`. Giải pháp: `resource_id` = UUID v3 (name-based) sinh
từ `"FILE_CATEGORY:" + code` / `"SETTING:" + key` (deterministic, lọc theo resource được), và
ghi `code`/`key` gốc vào `metadata.resourceKey` để hiển thị.

`AuditLogService` thêm overload `record(..., Map<String,Object> metadata, ...)` (hiện luôn ghi
`null`). Audit đổi cấu hình ghi **cùng transaction** với update (chỉ durable khi update
commit), tức dùng `record`, không dùng `recordIndependently`.

## 6. API (`/api/admin/**`, JSON, OpenAPI qua springdoc)

Phân trang chung (keyset): query `limit` (mặc định 50, tối đa 200), `cursor` (opaque,
base64 của `createdAt|id`). Response `{ items: [...], nextCursor: string | null }`.
Khoảng thời gian: `from`, `to` (ISO-8601 instant). Mặc định 30 ngày gần nhất; `to - from`
tối đa **92 ngày**, quá thì 400.

### 6.1 File — `file/api/admin/FileAdminController`

**`GET /api/admin/files`** — `file.asset.view`. Đây cũng là **lịch sử upload**: mỗi dòng
`file_assets` là một lần upload/sinh file, sắp xếp `created_at DESC`.
Filter: `from`, `to`, `categoryCode`, `storageStatus` (lặp được), `scanStatus`,
`mimeType` (khớp chính xác hoặc prefix kiểu `image/`), `uploadedBy` (UUID), `ownerType`,
`ownerId`, `filename` (ILIKE contains, chỉ áp trong khoảng thời gian đã giới hạn),
`source` = `USER_UPLOAD` (`job_id IS NULL`) | `JOB_GENERATED` (`job_id IS NOT NULL`).
Mặc định loại file đã soft-delete; `includeDeleted=true` để xem cả file đã xoá.
Item: `id, originalFilename, mimeType, sizeBytes, categoryCode, storageStatus, scanStatus,
ownerType, ownerId, uploadedBy, jobId, checksumSha256, createdAt, updatedAt, expiresAt,
deletedAt`. **Không** trả `bucketName`/`objectKey`. Không có cờ `multipart`: `multipart_upload_id`
bị xoá sau khi complete hoặc abort, nên không suy ra được cho file đã xong. *(Quyết định của Claude.)*

**`GET /api/admin/files/{id}`** — `file.asset.view`. Như item trên, cộng
`effectiveVirusScan` của category tại thời điểm hiện tại. Thấy cả file đã soft-delete. 404 nếu
không có.

**`GET /api/admin/files/{id}/history`** — `file.history.view`. Audit của riêng file này (mọi
action `resource_type = FILE_ASSET`, kể cả `READ`/`WRITE` DENIED), keyset.

**`GET /api/admin/files/stats`** — `file.asset.view`. Filter `from`, `to`, `categoryCode`.
Trả:
```json
{ "totalFiles": 0, "totalBytes": 0,
  "byStorageStatus": [{"key": "CLEAN", "files": 0, "bytes": 0}],
  "byCategory":      [{"key": "...", "files": 0, "bytes": 0}],
  "byMimeType":      [{"key": "application/pdf", "files": 0, "bytes": 0}],
  "daily":           [{"date": "2026-11-24", "files": 0, "bytes": 0}] }
```
`byMimeType`: top 10, phần còn lại gộp `"OTHER"`. `daily` theo ngày ở timezone
`app.admin.stats-zone` (mặc định `Asia/Ho_Chi_Minh`). Chỉ tính file chưa soft-delete. GROUP BY
trên khoảng thời gian đã giới hạn ≤ 92 ngày.

### 6.2 Lịch sử download — `shared/audit/api/AuditAdminController`

**`GET /api/admin/file-downloads`** — `file.history.view`.
Nguồn: `audit_logs` có `resource_type = 'FILE_ASSET'` và action thuộc
`FILE_DOWNLOAD_URL_ISSUED`, `FILE_DOWNLOADED_INTERNAL`, và `READ` có `result = DENIED`
(lượt bị từ chối). Filter `from`, `to`, `actorId`, `fileAssetId`, `result`, `channel` =
`PRESIGNED` | `INTERNAL`.
Item: `id, occurredAt, actorId, fileAssetId, channel, result, metadata`, cộng
`originalFilename, mimeType, sizeBytes, categoryCode` của file.
Việc ghép thông tin file: `shared/audit` **không** đọc `file_assets` trực tiếp. Controller
lấy trang audit, rồi gọi một interface `FileAssetSummaryLookup` (khai báo trong
`shared/audit`, module `file` implement và đặt ở `file/api`) với tối đa `limit` id → một
query `WHERE id IN (...)`. Cách này giống `ResourceOwnerLookup` đang có.

**Phạm vi:** lịch sử download **không** gồm file kết quả export. Link tải của export được
nhúng lại mỗi lần client hỏi trạng thái job, nên không phản ánh được lượt tải thật, và export đã
có audit trail riêng. Swagger của endpoint này ghi rõ điểm đó.

Dữ liệu cũ trước khi deploy: các lượt download cũ chỉ có `READ SUCCESS`, không có
`FILE_DOWNLOAD_URL_ISSUED` → không hiện trong lịch sử download (chấp nhận, ghi rõ ở Swagger).
Vẫn xem được qua `/api/admin/files/{id}/history`.

**`GET /api/admin/config-changes`** — `file.history.view`. Audit có action
`VIRUS_SCAN_GLOBAL_UPDATED` | `FILE_CATEGORY_UPDATED`. Filter `from`, `to`, `actorId`,
`resourceKey`. Item: `id, occurredAt, actorId, action, resourceKey, changes`.

### 6.3 Cấu hình — `file/api/admin/FileConfigAdminController`

**`GET /api/admin/virus-scan`** — `file.config.view`
```json
{ "globalEnabled": false, "version": 0, "updatedBy": null, "updatedAt": "...",
  "engineIntegrated": false,
  "categories": [{"code": "...", "requireVirusScan": false, "effective": false}] }
```
`effective = globalEnabled && requireVirusScan`. `engineIntegrated` là hằng `false` ở đợt
này, để console hiện cảnh báo "đã bật nhưng chưa có engine scan".

**`PUT /api/admin/virus-scan`** — `file.config.manage`. Body `{ "enabled": bool, "version":
long }`. Sai `version` → 409. Giá trị không đổi → 200, không ghi audit.

**`GET /api/admin/file-categories`** — `file.config.view`. Toàn bộ category (ít dòng, không
phân trang): `code, bucketName, allowedMimeTypes, maxSizeBytes, requireVirusScan,
effectiveVirusScan, publicAccess, retentionDays, multipartPartSizeBytes, version, updatedBy,
updatedAt`.

**`GET /api/admin/file-categories/{code}`** — `file.config.view`.

**`PATCH /api/admin/file-categories/{code}`** — `file.config.manage`. Body gồm `version`
(bắt buộc) và các trường tuỳ chọn `requireVirusScan`, `allowedMimeTypes`, `maxSizeBytes`,
`retentionDays`. **Không** cho sửa `code`, `bucketName`, `publicAccess`,
`multipartPartSizeBytes`: những trường này gắn với hạ tầng (bucket, lifecycle) hoặc với các
phiên multipart đang mở.
Validate: `allowedMimeTypes` không rỗng, mỗi phần tử dạng `type/subtype`, **không** wildcard
(`FileUploadService`/`MultipartUploadService` so khớp chính xác bằng `contains`); `maxSizeBytes`
> 0; `retentionDays` null hoặc > 0. Sai `version` → 409, không tồn tại → 404.
Thay đổi chỉ áp cho upload **mới**, file đã upload giữ nguyên.

Lỗi trả `ApiError` hiện có. `file.api.admin` đã nằm trong phạm vi của `FileApiExceptionHandler`
(`basePackages = file.api`), nên **thêm handler vào đó**: optimistic-lock → 409, validate
→ 400, range quá 92 ngày → 400. Không tạo advice mới cho `file`. `shared.audit.api` chỉ cần
handler cho lỗi tham số (400). 401/403 do security chain trả, không qua advice.

## 7. Ảnh hưởng tới code hiện có (tóm tắt)

- `pom.xml`: thêm 2 starter security.
- `FileCategory`: thêm `version` (`@Version`), `updatedBy`, method `applyAdminUpdate(...)`.
- `FileDownloadService.generateDownloadUrl`, `FileAssetStatusFinalizer.finalizeToClean`:
  ghi audit event mới. Hành vi trả về **không đổi**.
- `AuditLogService`: thêm overload có metadata.
- Luồng upload **không** đọc cờ scan ở đợt này.
- `application.yml`: `app.admin-security.*`, `app.admin.stats-zone`, JWKS URI.
- `vsp-identity-service`: một migration seed.

## 8. Test

Tên test theo quy ước `should_<expected>_when_<condition>`; phần DB/transaction ưu tiên
Testcontainers.


- Unit (Mockito): validate PATCH category, tính `effective`, encode/decode cursor, giới hạn
  khoảng thời gian, UUID v3 cho resource id.
- Integration (Testcontainers Postgres): keyset query của files/downloads/config-changes đúng
  thứ tự và không lặp hay sót khi qua trang; stats GROUP BY; optimistic lock trả 409; audit đổi
  cấu hình rollback cùng update.
- Security (MockMvc + `jwt()` của `spring-security-test`): mỗi endpoint admin trả 401 khi
  không có token, 403 khi thiếu đúng permission; **regression**: các endpoint hiện có
  (`/api/files/**`, `/api/internal/**`, `/exports`, `/imports`, `/api/templates`, actuator,
  swagger) chạy như cũ khi không có JWT; `enabled=false` + profile `prod` thì fail lúc khởi
  động.
- Audit: `GET /exports/{id}` không sinh `FILE_DOWNLOAD_URL_ISSUED`;
  `FILE_UPLOAD_COMPLETED` chỉ ghi một lần dù confirm idempotent;
  `FILE_DOWNLOAD_URL_ISSUED` không ghi khi file chưa CLEAN.

## 9. Để builder tự quyết

- Tên class/package chi tiết bên dưới các package đã nêu; JPQL, Criteria hay native SQL cho
  query động (lưu ý index ở 5.3).
- Định dạng cụ thể của cursor (chỉ cần opaque và ổn định).
- Chia changeset Liquibase thành bao nhiêu file (giữ đánh số nối tiếp từ 012).

## 10. Đợt sau (ngoài phạm vi)

- Tích hợp ClamAV: worker đọc `effective` để chuyển file UPLOADED_DMZ → SCANNING → CLEAN/INFECTED.
  Khi đó `engineIntegrated` = true.
- Frontend: `packages/api-file` (sinh từ OpenAPI của đợt này) + `platform/console/src/modules/file`.
