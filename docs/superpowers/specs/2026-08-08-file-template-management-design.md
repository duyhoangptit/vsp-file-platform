# Design Doc: File Template Management

Status: Approved for implementation
Date: 2026-08-08

## 1. Mục tiêu

Tính năng common cho phép quản lý file template (dùng để hướng dẫn định dạng cho các
tính năng import/export khác, ví dụ: member-import template, voucher-import template)
theo `category` (cụm nghiệp vụ lớn, VD: MEMBER, CLAIM) và `templateCode` (mã function,
VD: MEMBER_IMPORT). Mỗi `(category, templateCode)` có nhiều version; upload luôn tạo
version mới, download theo version cụ thể hoặc mặc định lấy version mới nhất, trả về
presigned URL.

## 2. Vị trí module

Module mới `com.company.platform.template`, ngang hàng `file/`, `job/`, `export/`,
`import_/` — gọi vào public API (`application`/`domain`) của `file` module, không tự
viết lại logic presigned URL/verify:

```
template/
├── domain/         # FileTemplate (entity), FileTemplateRepository, exceptions
├── application/    # FileTemplateService (business logic)
└── api/            # FileTemplateController, DTO (record)
```

## 3. Data model

### 3.1 `file_categories` — seed thêm 1 row

```
code=FILE_TEMPLATE, bucket_name=file-templates,
allowed_mime_types=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/csv"],
max_size_bytes=<cấu hình>, require_virus_scan=false, is_public=true, retention_days=NULL
```

`retention_days=NULL` vì template là tài liệu tham chiếu, không hết hạn tự động.

### 3.2 `file_template_manager` — bảng mới

| cột | kiểu | ghi chú |
|---|---|---|
| id | UUID PK | trùng với `file_assets.owner_id` của file được confirm tương ứng |
| category | VARCHAR(100) NOT NULL | |
| template_code | VARCHAR(100) NOT NULL | |
| version | INT NOT NULL | tăng dần theo `(category, template_code)`, không tái sử dụng |
| file_id | UUID NOT NULL, FK → file_assets.id | |
| description | VARCHAR(500) NULL | |
| created_by | UUID NOT NULL | |
| created_at | TIMESTAMP NOT NULL | |
| updated_by | UUID NULL | |
| updated_at | TIMESTAMP NOT NULL | |
| deleted_at | TIMESTAMP NULL | soft delete, chưa có endpoint dùng ở bản này |

Constraint: UNIQUE `(category, template_code, version)`.

"Latest version" = `MAX(version) WHERE category=? AND template_code=? AND deleted_at IS NULL`
— không dùng cờ `is_latest` riêng để tránh 2 nguồn sự thật.

## 4. Upload flow (2 bước, tái dùng `file` module)

**Quyền**: chỉ role `ADMIN`. Check inline trong `FileTemplateService`
(`user.roles().contains("ADMIN")` → `AccessDeniedException`), cùng pattern với
`ExportJobService.submitUserCsvExport` — role-check thuần, không phải owner-based nên
không dùng `@RequiresAccess`/`ResourceOwnerLookup`. Khác với `ExportJobService`, chủ
động ghi `audit_logs` (action `TEMPLATE_UPLOAD_INITIATE`, kết quả SUCCESS/DENIED) qua
`AuditLogService` cho cả 2 nhánh — đúng nguyên tắc mục 9 design doc gốc ("mọi lần check
kể cả DENIED ghi audit_logs"). Không sửa `ExportJobService` hiện có.

**Bước 1 — `POST /api/templates/upload/initiate`**

Request: `{ category, templateCode, originalFilename, mimeType, sizeBytes, description }`

- Check quyền ADMIN (ghi audit).
- Sinh `templateId = UUID.randomUUID()`.
- Gọi `FileUploadService.initiateUpload(...)` với `categoryCode=FILE_TEMPLATE`,
  `ownerType="FILE_TEMPLATE"`, `ownerId=templateId` → nhận `fileAssetId` + presigned PUT
  URL. Client upload thẳng MinIO (bypass backend), đúng flow hiện có ở `file` module.
- Trả về `{ fileAssetId, uploadUrl, expiresAt }`.
- **Chưa** tạo row `file_template_manager` ở bước này, tránh row rác nếu client bỏ dở
  upload (không cần thêm cơ chế dọn dẹp state chờ).

**Bước 2 — `POST /api/templates/upload/confirm`**

Request: `{ fileAssetId, category, templateCode, checksumSha256, description }`
(client gửi lại `category`/`templateCode`/`description` vì chưa có row nào lưu tạm).

- Gọi `FileUploadService.confirmUpload(fileAssetId, checksumSha256)` → `FileAsset`
  chuyển `CLEAN` (verify HEAD object như flow hiện có).
- Đọc `fileAsset.getOwnerId()` (chính là `templateId` sinh ở bước 1) → dùng làm `id`
  cho row `file_template_manager` mới.
- Trong cùng transaction: acquire `pg_try_advisory_xact_lock` (tái dùng
  `AdvisoryLockService`) với key = hash ổn định (`String.hashCode()`, được đặc tả ổn
  định qua các JVM) của `category + "|" + templateCode`, để 2 lần confirm đồng thời cho
  cùng `(category, templateCode)` không đụng version. Rủi ro hash trùng giữa 2 cặp khác
  nhau chỉ khiến chúng serialize hoá không cần thiết, không gây sai dữ liệu — chấp nhận
  được vì đây là thao tác quản trị tần suất thấp.
- Tính `version = COALESCE(MAX(version), 0) + 1 WHERE category=? AND template_code=?`,
  insert `file_template_manager`.
- Trả về entity vừa tạo.

## 5. Download flow

**`GET /api/templates/download-url?category=...&templateCode=...&version=...` (version optional)**

- Yêu cầu: đã đăng nhập (mọi request đã bắt buộc header `X-User-Id` qua
  `UserContextConfig`, không cần role riêng).
- Query `file_template_manager` theo `(category, templateCode)` + `version` nếu có,
  ngược lại lấy bản `MAX(version)` còn `deleted_at IS NULL`; không tìm thấy → 404
  (`FileTemplateNotFoundException`).
- Lấy `file_id` → gọi thẳng `FileDownloadService.generateDownloadUrl(fileId)` (module
  `file`) để sinh presigned GET — tái dùng nguyên bất biến "chỉ sinh URL khi
  storage_status = CLEAN", không viết lại logic presigned ở đây.
- Ghi `audit_logs` (action `TEMPLATE_DOWNLOADED`, resourceType `FILE_TEMPLATE`,
  resourceId = `file_template_manager.id`) sau khi sinh URL thành công.

## 6. API summary

| Method | Path | Quyền |
|---|---|---|
| POST | `/api/templates/upload/initiate` | ADMIN |
| POST | `/api/templates/upload/confirm` | ADMIN |
| GET | `/api/templates/download-url` | Authenticated (mọi role) |

## 7. Ngoài phạm vi (YAGNI)

- List tất cả version / tất cả template theo category.
- Endpoint xoá/deprecate version (cột `deleted_at` đã có sẵn cho tương lai).
- Virus scan thật — đi theo `SKIPPED`/`CLEAN` như category khác hiện tại (mục 5 design
  doc gốc).

## 8. Testing

- Unit test `FileTemplateService`: quyền ADMIN (allow/deny + audit), version tăng dần
  đúng cho nhiều lần confirm liên tiếp, download trả về đúng version theo tham số hoặc
  latest, 404 khi không tìm thấy.
- Integration test (Testcontainers Postgres) cho race condition version: nhiều
  transaction confirm đồng thời cùng `(category, templateCode)` không được sinh trùng
  version — verify qua advisory lock.
- End-to-end test tương tự `ExportUserCsvEndToEndIT`: initiate → (giả lập upload MinIO)
  → confirm → download-url, xuyên suốt 2 version trở lên.
