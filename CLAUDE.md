# Project: Job & File Management Platform

Modular monolith (Spring Boot) quản lý upload/download file và xử lý job nền
(export/import) chịu tải cao. Kiến trúc chi tiết: @docs/design/job-file-management.md

## Nguyên tắc bắt buộc (không được vi phạm)

- **Authorization** luôn check ở tầng API (Controller/Interceptor), KHÔNG bao giờ
  check trong worker/JobProcessor.
- **Status transition** của `jobs` và `file_assets` luôn qua CAS update
  (`WHERE status = '...'` + check `rowsAffected == 1`), không đọc-rồi-ghi.
- Mọi job phải implement qua `AbstractJobProcessor` (Template Method) — không tự
  viết flow heartbeat/status/error-handling riêng trong business processor.
- Business code trong `export/`, `import/` KHÔNG được import package của
  Kafka/Redis trực tiếp — chỉ phụ thuộc `JobEventPublisher` / `JobEventConsumer`.
- File object key luôn là UUID, KHÔNG dùng tên file gốc làm object key trên MinIO.
- Presigned download URL chỉ sinh khi `file_assets.storage_status = 'CLEAN'`.
- Query dữ liệu lớn dùng keyset pagination (`WHERE id > :lastId ORDER BY id`),
  KHÔNG dùng OFFSET lớn, KHÔNG load full list vào memory.
- Module chỉ được gọi vào package `api` (hoặc `*.public`) của module khác,
  không đụng vào package `internal`.
- **`DataReader` (dùng trong Export `KitFactory`) luôn nhận `cursor` + `pageSize`
  thuần, KHÔNG nhận thẳng `EntityManager`/`Repository`/DB-specific type làm tham
  số** — vì nguồn dữ liệu export có thể là DB local (Phase 4) hoặc API của service
  khác (khi tách microservice), interface phải trung lập với nguồn dữ liệu:

  ```java
  interface DataReader<T> {
      Page<T> readNextPage(String cursor, int pageSize);
  }

  // impl khi data cùng DB (hiện tại)
  class LocalDbDataReader<T> implements DataReader<T> { ... }

  // impl khi data ở service khác (tương lai) — gọi REST/gRPC nội bộ,
  // service business tự kiểm soát tải/rate-limit ở tầng API của họ
  class RemoteApiDataReader<T> implements DataReader<T> { ... }
  ```

  `ExportKitFactory.createReader()` quyết định trả về implementation nào theo
  `job_type`; phần còn lại của pipeline (Writer, Uploader, status, heartbeat)
  không đổi. Xem thêm mục 7 design doc (Cross-Service Data Access).

## Cấu trúc thư mục

```
src/main/java/com/company/platform/
├── file/
│   ├── api/                # Controller, Request/Response DTO
│   ├── domain/              # FileAsset, FileCategory (entity + repository interface)
│   ├── storage/              # MinioStorageService, PresignedUrlService
│   ├── application/           # FileUploadService, FileDownloadService (use case)
│   └── internal/               # impl chi tiết, package-private, không expose
│
├── job/
│   ├── api/                 # Controller, DTO
│   ├── domain/                # Job, JobStatus, JobType (entity + repository)
│   ├── processor/              # JobProcessor, AbstractJobProcessor, JobProcessorFactory
│   ├── scheduler/               # JobScheduler (SELECT ... FOR UPDATE SKIP LOCKED)
│   ├── messaging/                # JobEventPublisher/Consumer (interface)
│   │   ├── kafka/                   # KafkaJobEventPublisher/Consumer (adapter)
│   │   └── redis/                    # RedisJobEventPublisher/Consumer (adapter)
│   └── internal/
│
├── export/
│   └── csv/                  # ExportCsvProcessor, CsvExportKitFactory
│
├── import_/                  # "import" là từ khoá Java, đặt tên package import_
│   └── user/                  # ImportUserProcessor, UserImportKitFactory
│
└── shared/
    ├── auth/                  # ResourceAuthorizationService, UserContext
    ├── audit/                  # AuditLogService (append-only)
    ├── ratelimit/               # RateLimiterService (Redis token bucket)
    └── config/                  # Common config, properties classes

src/main/resources/
├── db/changelog/             # Liquibase: db.changelog-master.yaml + changeset files
│   ├── db.changelog-master.yaml
│   └── changes/
│       ├── 001-init-jobs.yaml
│       ├── 002-init-file-assets.yaml
│       └── 003-init-audit-logs.yaml
└── application.yml

src/test/java/...             # Mirror cấu trúc main, dùng Testcontainers cho
                               # Postgres/MinIO/Kafka trong integration test
```

## Tech stack & version

- Java 21 (JDK 21), Spring Boot 4.1.
- PostgreSQL, MinIO, Kafka, Redis.
- Build tool: Maven.
- Dockerfile và Docker Compose đã có sẵn trong repo — KHÔNG tự tạo mới, dùng file
  hiện có khi cần chạy hạ tầng local:
  - `Dockerfile`
  - `compose.yaml`
  - Chạy hạ tầng local: `docker compose up -d`

## Build & Run

```bash
# Build (kèm format check — xem mục Spotless bên dưới)
mvn clean install

# Chỉ format lại code trước khi commit
mvn spotless:apply

# Kiểm tra format mà không sửa (dùng trong CI)
mvn spotless:check

# Chạy Liquibase migration thủ công (thường Spring Boot tự chạy khi start app)
mvn liquibase:update

# Rollback migration gần nhất (dùng thận trọng, chỉ ở local/dev)
mvn liquibase:rollback -Dliquibase.rollbackCount=1

# Run local (profile dev, đọc application-dev.yml)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Unit test
mvn test

# Integration test (cần Docker chạy sẵn cho Testcontainers)
mvn verify -Pintegration-test
```

## Code format — Spotless

Project dùng `spotless-maven-plugin` để kiểm soát format thống nhất, bind vào
phase `validate` của Maven — nghĩa là `mvn clean install` sẽ **fail build nếu code
chưa format đúng chuẩn**. Luôn chạy `mvn spotless:apply` trước khi commit hoặc
trước khi báo hoàn thành 1 task.

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>2.44.0</version> <!-- kiểm tra version mới nhất khi setup lần đầu -->
  <configuration>
    <java>
      <googleJavaFormat>
        <version>1.24.0</version>
        <style>GOOGLE</style>
      </googleJavaFormat>
      <removeUnusedImports/>
      <importOrder/>
      <trimTrailingWhitespace/>
      <endWithNewline/>
    </java>
  </configuration>
  <executions>
    <execution>
      <id>spotless-check</id>
      <phase>validate</phase>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Khi Claude sinh code mới, KHÔNG tự ý đổi style format (thụt lề, dấu ngoặc, thứ tự
import) khác với những gì Spotless quy định ở trên — cứ viết code bình thường rồi
chạy `mvn spotless:apply` để chuẩn hoá, không cần format tay.

## Code style

- Tên class theo pattern đã chốt: `{Domain}Processor`, `{Domain}KitFactory`,
  `{Domain}Service` (application service), `{Domain}Repository` (Spring Data JPA).
- DTO tách biệt hoàn toàn khỏi Entity — không trả Entity trực tiếp ra Controller.
- Dùng `record` (Java record) cho DTO bất biến thay vì Lombok `@Data` cho response object.
- Constructor injection only — không dùng `@Autowired` trên field.
- Exception: domain exception riêng (`JobNotFoundException`, `FileAccessDeniedException`),
  xử lý tập trung qua `@ControllerAdvice`, không try-catch rải rác trong Controller.
- Test: đặt tên `should_<expected>_when_<condition>`, ưu tiên Testcontainers hơn mock
  cho phần liên quan DB transaction/CAS (race condition chỉ lộ ra khi test với DB thật).

## Khi thêm 1 loại job mới (checklist)

1. Thêm `JobType` enum value.
2. Viết `{Name}Processor extends AbstractJobProcessor`, implement `validate()` + `doExecute()`.
3. Nếu export/import: viết thêm `{Name}KitFactory` nếu cần bộ Reader/Writer/Uploader riêng.
4. Đăng ký `file_categories` mới nếu job sinh ra loại file mới (bucket, mime type, retention).
5. KHÔNG sửa `JobProcessorFactory`, `AbstractJobProcessor`, `JobScheduler`, hay
   `JobEventConsumer` — nếu thấy cần sửa các file này, dừng lại và hỏi trước khi làm,
   vì đó là dấu hiệu vi phạm Open/Closed của kiến trúc đã chốt.

## Khi không chắc chắn

Nếu 1 task yêu cầu thay đổi kiến trúc đã mô tả trong
`docs/design/job-file-management.md` (VD: đổi state machine, đổi cách CAS, đổi
ranh giới module) — dừng lại, giải thích ngắn gọn vì sao cần đổi, và hỏi trước
khi code thay vì tự quyết định rồi triển khai.