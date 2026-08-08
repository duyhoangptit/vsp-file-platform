# Design Doc: API downloadFileBytes cho internal call

Status: Approved for implementation
Date: 2026-08-08

## 1. Mục tiêu

Thêm 1 API mới, song song với API sinh presigned download URL hiện có
(`GET /api/files/{fileAssetId}/download-url`), để các service nội bộ (server-to-server,
không đại diện cho 1 end-user cụ thể) có thể tải trực tiếp nội dung byte của file đã
upload mà không cần đi qua MinIO bằng presigned URL. Không thay đổi hành vi endpoint
hiện có.

## 2. Endpoint & contract

`GET /api/internal/files/{fileAssetId}/bytes` — controller mới `FileInternalDownloadController`
trong package `file/api` (cùng module `file`, không đụng `FileDownloadController` hiện có).

Dùng prefix `/api/internal/**` (thay vì gắn thêm path con dưới `/api/files/**`) để:
- interceptor auth-key chỉ cần đăng ký 1 pattern chung, tái dùng được cho các API
  internal khác thêm sau này;
- gateway/ingress có thể chặn theo prefix này như 1 lớp phòng thủ bổ sung ở tầng network.

Response: raw bytes streaming (`ResponseEntity<StreamingResponseBody>`), không buffer
full file vào RAM:
- `Content-Type` = `file_assets.mime_type`
- `Content-Disposition: attachment; filename="..."` — dựng qua
  `org.springframework.http.ContentDisposition` (an toàn RFC 5987, tránh tự nối string
  bị injection qua filename)
- `Content-Length` = `size_bytes`

Giữ nguyên bất biến "chỉ tải được khi CLEAN" (mục 5 design doc gốc), dùng lại 2
exception đã có: `FileAssetNotFoundException` (404), `FileNotAvailableForDownloadException`
(409) — xử lý qua `FileApiExceptionHandler` hiện có (scan theo package `file.api`).

## 3. Authorization — service-to-service, không dùng @RequiresAccess

Endpoint này không gắn với 1 end-user cụ thể (không có `UserContext` đáng tin cậy từ
phía caller), nên không dùng `@RequiresAccess`/`ResourceAuthorizationService` (vốn thiết
kế cho end-user ownership check). Thay vào đó dùng shared-secret header, verify ở tầng
Interceptor — vẫn đúng nguyên tắc bắt buộc "authorization luôn check ở tầng API
(Controller/Interceptor)":

- `InternalApiKeyProperties` — `@ConfigurationProperties(prefix = "app.internal-api")`,
  field `key` (String), validate `@NotBlank` — fail-fast lúc start nếu thiếu cấu hình,
  không đặt giá trị default trong `application.yml` (bắt buộc set qua
  `APP_INTERNAL_API_KEY`).
- `InternalApiKeyInterceptor implements HandlerInterceptor` — check header
  `X-Internal-Api-Key` bằng `MessageDigest.isEqual` (constant-time so sánh, tránh timing
  attack); thiếu hoặc sai → ném `UnauthenticatedException` (đã có sẵn, đã map 401 qua
  `AuthExceptionHandler` toàn cục).
- Đăng ký qua `WebMvcConfigurer.addInterceptors()`, chỉ áp dụng path pattern
  `/api/internal/**` — không ảnh hưởng các endpoint public khác.

Vì bypass `@RequiresAccess` cũng bỏ qua audit tự động của `ResourceAccessAuditAspect`,
ghi audit tường minh ngay trong service khi tải thành công:
`AuditLogService.record(actorId=null, action="FILE_DOWNLOADED_INTERNAL",
resourceType="FILE_ASSET", resourceId=fileAssetId, result=SUCCESS)` — `actor_id=null`
hợp lệ theo schema (audit_logs, mục 3.4 design doc gốc), đúng nghĩa "system/service
trigger".

## 4. Application layer

- Thêm `FileDownloadService.prepareBytesDownload(UUID fileAssetId)` — tái dùng cùng
  logic tìm + check CLEAN với `generateDownloadUrl` (refactor 1 helper private dùng
  chung: `requireCleanFileAsset(UUID)`), sau đó gọi `minioStorageService.getObject(...)`.
- DTO mới `FileBytesResult(InputStream content, String originalFilename, String mimeType,
  long sizeBytes)` trong package `file/application`.
- Controller mở `StreamingResponseBody`, `try-with-resources` đảm bảo đóng `InputStream`
  kể cả khi client ngắt kết nối giữa chừng hoặc ghi lỗi.

## 5. Config

`application.yml`:
```yaml
app:
  internal-api:
    key: ${APP_INTERNAL_API_KEY:}
```
Không set default thật — để trống buộc phải cấu hình tường minh ở mọi môi trường.
`application-dev.yml` set 1 giá trị dev-only cho tiện chạy local.

## 6. Test

- Unit: `InternalApiKeyInterceptorTest` (thiếu key / sai key / đúng key).
- Unit: `FileDownloadServiceTest` bổ sung case cho `prepareBytesDownload` (not-found,
  not-CLEAN, happy path trả đúng metadata + gọi `minioStorageService.getObject`).
- Controller slice test (`@WebMvcTest`) cho `FileInternalDownloadController`: 401 khi
  thiếu/sai header, header response đúng khi thành công (mock service).
- Integration (Testcontainers, cùng kiểu `AvatarUploadDownloadEndToEndIT`): upload thật
  → confirm → gọi endpoint bytes với key đúng → so khớp nội dung byte-for-byte; gọi với
  key sai/thiếu → 401.

## 7. Ngoài phạm vi

- Không đổi endpoint `/api/files/{fileAssetId}/download-url` hiện có.
- Không hỗ trợ nhiều key/nhiều caller phân biệt (1 shared key dùng chung cho mọi internal
  caller) — nếu sau này cần phân biệt/rotate theo từng service, mở rộng
  `InternalApiKeyProperties` sang tập nhiều key, không thuộc phạm vi bản này (YAGNI).
