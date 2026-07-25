# Design Doc: Job Processing & File Management Platform

Status: Approved for implementation
Architecture style: Modular Monolith (candidate boundaries for future microservice split marked below)
Techstack: Java Spring Boot, PostgreSQL, MinIO, Kafka/Redis (broker-agnostic)

---

## 1. Mục tiêu

Xây dựng nền tảng chung xử lý:
- **Upload/Download file** (avatar, ảnh sản phẩm, file export/import) — an toàn, không đè file, chuẩn bị sẵn cho virus scan.
- **Export/Import chịu tải cao** (chạy nền, không block request, không sập DB/hết tài nguyên server).
- Dùng chung 1 pipeline cho mọi loại nghiệp vụ (export CSV, export Excel, import user, import product...), mở rộng loại mới không sửa code cũ.

---

## 2. Module Boundaries (Modular Monolith)

```
com.company.platform
├── file/     → File Management module (candidate: file-service)
├── job/      → Job Processing / Async Task Platform (candidate: job-service)
├── export/   → Business processors dùng file + job module
├── import/   → Business processors dùng file + job module
└── shared/   → UserContext, AuditLog, RateLimiter dùng chung toàn app
```

Quy tắc: module chỉ gọi vào `api`/public interface của module khác, không đụng `internal` package. Enforce bằng ArchUnit hoặc Spring Modulith. Đây là ranh giới tự nhiên để tách microservice sau này (`file-service`, `job-service`) khi có tín hiệu rõ về tải/team.

---

## 3. Data Model

### 3.1 `jobs` — bảng chung cho mọi loại job (export, import, report...)

```sql
jobs (
  id                UUID PK,
  job_type          VARCHAR,     -- EXPORT_USER_CSV, IMPORT_PRODUCT, ...
  status            VARCHAR,     -- xem state machine mục 4
  priority          SMALLINT,    -- 0=normal, 1=high, 2=urgent
  payload           JSONB,       -- tham số riêng từng loại job
  result_location   VARCHAR,     -- object_key file kết quả (nullable)
  idempotency_key   VARCHAR UNIQUE,
  total_units       BIGINT,
  processed_units   BIGINT,
  heartbeat_at      TIMESTAMP,
  retry_count       INT DEFAULT 0,
  error_message     TEXT,
  created_by        UUID FK users.id,
  created_at        TIMESTAMP,
  updated_at        TIMESTAMP
);

INDEX (created_by, status);
INDEX (status, priority DESC, created_at ASC);  -- phục vụ JobScheduler
```

### 3.2 `file_categories` — cấu hình theo business type

```sql
file_categories (
  code                  VARCHAR PK,   -- AVATAR, PRODUCT_IMAGE, EXPORT_RESULT, IMPORT_RAW
  bucket_name           VARCHAR,
  allowed_mime_types    JSONB,
  max_size_bytes        BIGINT,
  require_virus_scan    BOOLEAN DEFAULT false,  -- future-proof
  is_public             BOOLEAN DEFAULT false,
  retention_days        INT NULL,
  created_at, updated_at TIMESTAMP
);
```

### 3.3 `file_assets` — registry tập trung mọi file

```sql
file_assets (
  id                 UUID PK,
  category_code      FK file_categories.code,
  bucket_name        VARCHAR,          -- denormalize để query nhanh
  object_key         VARCHAR,          -- UUID-based, KHÔNG dùng tên gốc
  original_filename  VARCHAR,          -- chỉ để hiển thị UI
  mime_type          VARCHAR,
  size_bytes         BIGINT,
  checksum_sha256    VARCHAR,
  storage_status     VARCHAR,          -- UPLOADING -> UPLOADED_DMZ -> SCANNING -> CLEAN | INFECTED -> QUARANTINED
  scan_status        VARCHAR,          -- SKIPPED / PENDING / CLEAN / INFECTED
  owner_type         VARCHAR,          -- polymorphic: USER, PRODUCT, JOB...
  owner_id           UUID,
  uploaded_by        FK users.id,
  job_id             FK jobs.id NULL,
  expires_at         TIMESTAMP NULL,
  created_at, updated_at TIMESTAMP,
  deleted_at         TIMESTAMP NULL
);

UNIQUE (bucket_name, object_key);
INDEX (owner_type, owner_id);
INDEX (category_code, storage_status, expires_at);
INDEX (job_id);
```

### 3.4 `audit_logs` — append-only

```sql
audit_logs (
  id            UUID PK,
  actor_id      FK users.id NULL,   -- null = system trigger
  action        VARCHAR,            -- JOB_CREATED, FILE_DOWNLOADED, ACCESS_DENIED...
  resource_type VARCHAR,            -- JOB, FILE_ASSET
  resource_id   UUID,
  metadata      JSONB,              -- filter params, IP, user-agent
  result        VARCHAR,            -- SUCCESS, DENIED, FAILED
  created_at    TIMESTAMP
);

INDEX (resource_type, resource_id, created_at);
INDEX (actor_id, created_at);
```

Ghi log tại 2 điểm: `ResourceAuthorizationService` (kể cả DENIED) và `JobStatusService.transition()`.

---

## 4. State Machine — `jobs.status`

```
PENDING → PROCESSING → DONE
                     → FAILED (retry_count < max → về lại PENDING, ngược lại dừng hẳn)
DONE → EXPIRED (retention job dọn dẹp)
```

- Chuyển `PENDING → PROCESSING` bắt buộc qua **CAS update** (`WHERE status='PENDING'`, check `rowsAffected == 1`) — đảm bảo không có 2 worker cùng nhận 1 job.
- `heartbeat_at` update định kỳ khi PROCESSING. Reconciler job quét `PROCESSING` mà heartbeat quá cũ (>5 phút) → set `FAILED` (worker crash/timeout). Không cần resume — export/import fail thì yêu cầu chạy lại (rẻ, chấp nhận được vì read-only, idempotent).

## 5. `file_assets.storage_status` — DMZ-ready (chưa bật scan)

```
UPLOADING → UPLOADED_DMZ → CLEAN            (hiện tại: skip SCANNING, đi thẳng)
UPLOADING → UPLOADED_DMZ → SCANNING → CLEAN | INFECTED → QUARANTINED   (khi bật virus scan)
```

Nguyên tắc bất biến: API sinh presigned **download URL chỉ cho phép khi `storage_status = CLEAN`** — áp dụng ngay từ bây giờ dù chưa scan thật, để không phải sửa lại logic khi bật scan sau này.

Khi bật scan: presigned upload trỏ bucket `{category}-dmz`, thêm `VirusScanProcessor` (theo đúng JobProcessor pattern ở mục 6), scan xong copy sang bucket `-clean` (server-side copy MinIO).

---

## 6. Job Processing Pipeline

### 6.1 Pattern áp dụng

- **Factory Method**: `JobProcessorFactory.getProcessor(jobType)` — Spring tự inject `List<JobProcessor>`, build map lúc `@PostConstruct`. Thêm loại job mới = thêm 1 class, không sửa code cũ.
- **Template Method**: `AbstractJobProcessor` lo phần hạ tầng chung (CAS status, heartbeat, error handling, cleanup) — business class chỉ implement `validate()` + `doExecute()`.
- **Abstract Factory**: `ExportKitFactory` / `ImportKitFactory` tạo họ object phải khớp định dạng nhau (`DataReader` + `RecordWriter` + `ResultUploader` cho export; `FileParser` + `SchemaValidator` + `RecordPersister` cho import). Tránh trộn reader CSV với writer Excel.

```
interface JobProcessor {
    JobType supports();
    void process(JobContext context);
}

abstract class AbstractJobProcessor implements JobProcessor {
    final void process(JobContext ctx) {
        if (!tryMarkProcessing(ctx.jobId)) return;
        startHeartbeat(ctx.jobId);
        try {
            validate(ctx.payload);
            Result r = doExecute(ctx);
            markDone(ctx.jobId, r);
        } catch (Exception e) {
            markFailed(ctx.jobId, e);
        } finally {
            stopHeartbeat(ctx.jobId);
        }
    }
    protected abstract void validate(JsonNode payload);
    protected abstract Result doExecute(JobContext ctx);
}
```

### 6.2 Message Broker — Port/Adapter (broker-agnostic)

```
interface JobEventPublisher { void publish(JobMessage message); }
interface JobEventConsumer  { void onMessage(JobMessage message, Acknowledgement ack); }
```

- Adapter riêng: `KafkaJobEventPublisher`, `RedisJobEventPublisher` — chọn qua `messaging.provider=kafka|redis` (Spring Profile / `@ConditionalOnProperty`).
- Business code (`export/`, `import/`) **không import package Kafka/Redis**, chỉ phụ thuộc interface.
- **Message chỉ là "wake-up signal"**, KHÔNG phải nguồn tin cậy duy nhất — worker luôn re-check + CAS status trong DB. Điều này cho phép broker-agnostic thật sự (Redis Pub/Sub không persist, Kafka có persist — cả 2 đều hoạt động đúng nhờ DB là source of truth).
- Vì Redis Pub/Sub có thể mất message → bắt buộc có **fallback poller** (scheduled task quét `jobs WHERE status='PENDING' AND created_at < now() - interval '30s'` để tự trigger lại). Đây cũng là lưới bảo hiểm chung cho cả Kafka nếu consumer có bug.
- Idempotency: `idempotency_key` UNIQUE constraint ở DB (chặn tại API bằng `ON CONFLICT DO NOTHING`), dùng `jobId` làm Kafka message key (đảm bảo cùng partition/thứ tự) + CAS status là đủ, không cần bảng dedup riêng.

### 6.3 Job Scheduling & Priority

Priority KHÔNG xử lý ở tầng message broker (Kafka/Redis không có priority queue tốt). Thay vào đó:

```sql
SELECT * FROM jobs
WHERE status = 'PENDING'
ORDER BY priority DESC, created_at ASC
LIMIT 1
FOR UPDATE SKIP LOCKED;   -- Postgres, tránh nhiều worker instance giành cùng job
```

Message trên queue chỉ để "đánh thức" consumer đi query DB — quyền quyết định thứ tự chạy luôn nằm ở DB.

### 6.4 Giới hạn tài nguyên

| Giới hạn | Cơ chế |
|---|---|
| DB connection pool export | HikariCP pool riêng, tách khỏi pool traffic chính |
| Query lớn không tràn RAM | Keyset pagination (`WHERE id > lastId`), JDBC `fetchSize`, streaming trực tiếp vào MinIO |
| Số job PROCESSING đồng thời/instance | Semaphore/executor pool riêng trong worker, hoặc Kafka consumer `concurrency` |
| Số job PENDING/PROCESSING của 1 user | Check `COUNT(*) FROM jobs WHERE created_by=? AND status IN (PENDING,PROCESSING)` trước khi tạo job mới, từ chối nếu vượt ngưỡng config |
| Rate limit tạo job | Redis Token Bucket/Sliding Window theo `user:{id}:job:create`, ngưỡng theo `job_type` |
| File lớn | Chia part (CSV/zip nhiều file), MinIO multipart upload |

---

## 7. Cross-Service Data Access (khi tách microservice)

4 hướng, chọn theo giai đoạn hệ thống:

1. **Gọi API nội bộ** (REST/gRPC, pagination cursor, service-to-service auth) — mặc định khi đã tách microservice thật.
2. **Đọc trực tiếp DB khác** (DB user riêng, chỉ SELECT, ưu tiên read replica) — chấp nhận tạm trong modular monolith, KHÔNG dùng khi DB đã tách biệt hoàn toàn.
3. **CDC/Data pipeline riêng** (Debezium → read-optimized store) — khi export/report dùng thường xuyên trên cùng tập dữ liệu, đầu tư 1 lần phục vụ nhiều nhu cầu.
4. **Plugin model**: business service tự viết `JobProcessor` của mình (implement interface từ `job` module qua shared library), chạy trong chính process/worker của service đó, tự do query DB nội bộ — tôn trọng bounded context tốt nhất.

**Hiện tại (modular monolith)**: dùng hướng 2 có kiểm soát. Khi tách service: chuyển hướng 1 làm mặc định, cân nhắc hướng 4 nếu muốn giữ trách nhiệm business rõ ràng.

---

## 8. File Upload/Download Flow

- Presigned PUT URL sinh theo `category_code` (biết trước file thuộc loại nào để chọn bucket + policy MinIO — Content-Length-Range, Content-Type restriction ngay ở presigned policy).
- Client upload thẳng MinIO (bypass backend) → giảm tải CPU/RAM server.
- Client confirm sau upload → backend verify (HEAD object, checksum) → ghi `file_assets`.
- Download: API check `ResourceAuthorizationService` trước → nếu pass, sinh presigned GET URL có thời hạn ngắn (15-30 phút), chỉ khi `storage_status='CLEAN'`.
- Object key = UUID, không dùng tên file gốc → tránh đè file, tránh lộ thông tin qua URL.

---

## 9. Authorization

```
interface ResourceAuthorizationService {
    boolean canAccess(UserContext user, ResourceType type, String resourceId, Action action);
}
```

- Rule mặc định: `owner_id == user.id` OR `user.hasRole(ADMIN)` OR scope phù hợp (OAuth2 scope nếu có).
- Check **ở tầng API**, trước khi tạo job/sinh presigned URL — KHÔNG check ở worker (worker tin job đã được authorize lúc tạo).
- Áp dụng như AOP/interceptor chung, không rải if-else trong từng Controller.
- Mọi lần check (kể cả DENIED) ghi vào `audit_logs`.

---

## 10. Retention & Cleanup

- **MinIO Lifecycle Policy** trên bucket/prefix: tự động Expire sau X ngày theo `file_categories.retention_days`.
- **Scheduled job** quét `file_assets` có `status=DONE` (job) hết hạn → set `EXPIRED`, dọn metadata (file vật lý đã bị MinIO lifecycle xoá).
- Giữ record DB (đổi status) thay vì xoá hẳn nếu cần audit trail lâu dài.

---

## 11. Client UX cho polling status

- Progress theo giai đoạn (Chuẩn bị → Ghi file → Upload → Hoàn tất) nếu không ước tính được `total_units` chính xác; % thực nếu có.
- Polling interval tăng dần (1s → 2s → 3s → 5s, cap 10s), hoặc SSE nếu hạ tầng cho phép.
- Không block UI — cho phép rời trang, thông báo qua in-app notification / email khi xong.
- Lỗi hiển thị lý do dễ hiểu + nút "Thử lại" (tạo job mới cùng filter).
- Chặn double-submit: nếu đã có job PENDING/PROCESSING cùng filter, show job đang chạy thay vì tạo mới.

---

## 12. Chưa quyết định — để config, không hardcode

- Số job PENDING tối đa/user
- Rate limit request/phút theo job_type
- Retention days theo từng category
- Ngưỡng heartbeat timeout, số lần retry tối đa

Lưu trong DB config table hoặc `application.yml`, chỉnh không cần deploy lại.

---

## 13. Implementation Roadmap

| Phase | Nội dung |
|---|---|
| 1 | Schema DB (`jobs`, `file_assets`, `file_categories`, `audit_logs`) + Liquibase changelog |
| 2 | File module: MinIO wrapper, presigned URL, `ResourceAuthorizationService` cơ bản — test bằng upload avatar |
| 3 | Job module: state machine, `JobProcessorFactory`, `AbstractJobProcessor`, `JobEventPublisher/Consumer` (interface trước, 1 adapter sau) |
| 4 | 1 business case cụ thể (export CSV) chạy end-to-end |
| 5 | Rate limit, priority scheduler, retention/cleanup job |
| 6 | Audit log interceptor, thêm business case thứ 2 (import) để verify tính mở rộng |