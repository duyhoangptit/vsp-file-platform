# Multipart Upload for Large Files — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a separate `/api/files/multipart` API that lets clients upload large files to MinIO in parallel resumable parts, without touching the existing single presigned-PUT flow.

**Architecture:** Client-orchestrated S3 multipart upload. Backend opens/closes the multipart session and presigns each part; the browser PUTs parts straight to MinIO. The `file_assets` state machine is unchanged — multipart is just the transport, and the object still goes `UPLOADING → UPLOADED_DMZ → CLEAN` through the same CAS transitions as `confirmUpload`. MinIO Java SDK 9.0.0 does not expose multipart lifecycle calls, so an isolated AWS SDK v2 `S3Client`/`S3Presigner` (pointed at the same MinIO endpoint) drives create/complete/abort/listParts; the MinIO SDK stays for everything else.

**Tech Stack:** Java 21, Spring Boot 4.1, PostgreSQL, MinIO, Liquibase, `software.amazon.awssdk:s3` 2.54.11, Testcontainers.

**Spec:** `docs/design/job-file-management.md` section 8.1 ("Multipart upload cho file lớn").

## Global Constraints

- Spotless (`googleJavaFormat` GOOGLE style) is bound to Maven `validate` — run `mvn spotless:apply` before every commit. Do not hand-format.
- Authorization only at the API layer (`@RequiresAccess`), never in services touching storage.
- `file_assets` status transitions only via CAS (`WHERE storage_status = :expected`, check `rowsAffected == 1`).
- Object key is always a random UUID, never the original filename.
- Presigned download URL only when `storage_status = 'CLEAN'` (unchanged; not touched here).
- Modules call only `api`/public packages of other modules, never `internal`.
- DTOs are Java `record`, separate from entities. Constructor injection only, no `@Autowired` fields.
- Domain exceptions handled centrally via `@RestControllerAdvice` (`FileApiExceptionHandler`), no try/catch in controllers.
- Do NOT modify `JobProcessorFactory`, `AbstractJobProcessor`, `JobScheduler`, `JobEventConsumer`.
- Test naming: `should_<expected>_when_<condition>`. Prefer Testcontainers over mocks for DB/CAS and for anything hitting MinIO.
- New AWS SDK imports (`software.amazon.awssdk.*`) may appear ONLY under package `com.company.platform.file.storage.multipart`.

---

## File Structure

**Created:**
- `src/main/resources/db/changelog/changes/011-init-multipart-upload.yaml` — 2 nullable columns.
- `src/main/java/com/company/platform/file/storage/multipart/MultipartS3Config.java` — `S3Client` + `S3Presigner` beans from `MinioProperties`.
- `src/main/java/com/company/platform/file/storage/multipart/MultipartStorageService.java` — thin adapter over AWS SDK: create / presignPart / listParts / complete / abort.
- `src/main/java/com/company/platform/file/storage/multipart/MultipartStorageException.java` — runtime wrapper.
- `src/main/java/com/company/platform/file/storage/multipart/UploadedPart.java` — record `(int partNumber, String etag, long sizeBytes)`.
- `src/main/java/com/company/platform/file/storage/multipart/CompletedPartRef.java` — record `(int partNumber, String etag)`.
- `src/main/java/com/company/platform/file/application/FileAssetStatusFinalizer.java` — shared "verify + CAS to CLEAN" extracted from `FileUploadService.confirmUpload`.
- `src/main/java/com/company/platform/file/application/MultipartUploadService.java` — use-case orchestration.
- `src/main/java/com/company/platform/file/application/InitiateMultipartUploadCommand.java` — record.
- `src/main/java/com/company/platform/file/application/MultipartUploadInitiation.java` — record `(UUID fileAssetId, long partSizeBytes, int maxParts)`.
- `src/main/java/com/company/platform/file/domain/MultipartNotEnabledException.java`
- `src/main/java/com/company/platform/file/domain/InvalidPartNumberException.java`
- `src/main/java/com/company/platform/file/api/FileMultipartUploadController.java`
- `src/main/java/com/company/platform/file/api/InitiateMultipartUploadRequest.java`
- `src/main/java/com/company/platform/file/api/InitiateMultipartUploadResponse.java`
- `src/main/java/com/company/platform/file/api/PartUrlsRequest.java`
- `src/main/java/com/company/platform/file/api/PartUrlsResponse.java`
- `src/main/java/com/company/platform/file/api/UploadedPartsResponse.java`
- `src/main/java/com/company/platform/file/api/CompleteMultipartUploadRequest.java`
- Tests mirroring the above under `src/test/java/...`.

**Modified:**
- `pom.xml` — add `<dependencyManagement>` (awssdk BOM) + `s3` + `url-connection-client`.
- `src/main/resources/db/changelog/db.changelog-master.yaml` — include 011.
- `src/main/java/com/company/platform/file/domain/FileCategory.java` — `+ Long multipartPartSizeBytes`.
- `src/main/java/com/company/platform/file/domain/FileAsset.java` — `+ String multipartUploadId` + mutators.
- `src/main/java/com/company/platform/file/domain/FileAssetRepository.java` — `+ updateSizeBytes`, `+ clearMultipartUploadId`, `+ softDelete`, `+ findStaleMultipartUploads`.
- `src/main/java/com/company/platform/file/application/FileUploadService.java` — `confirmUpload` delegates to `FileAssetStatusFinalizer`.
- `src/main/java/com/company/platform/file/api/FileApiExceptionHandler.java` — map 2 new exceptions.
- `src/main/java/com/company/platform/shared/cleanup/CleanupService.java` — `+ abortStaleMultipartUploads()`.
- `src/main/java/com/company/platform/shared/cleanup/CleanupProperties.java` — `+ Duration multipartUploadTimeout`.
- `src/main/resources/application.yml` — `app.cleanup.multipart-upload-timeout`.
- `CLAUDE.md` — mention `file/storage/multipart/` in directory structure.

---

## Task 1: DB schema + entity columns

**Files:**
- Create: `src/main/resources/db/changelog/changes/011-init-multipart-upload.yaml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `src/main/java/com/company/platform/file/domain/FileCategory.java`
- Modify: `src/main/java/com/company/platform/file/domain/FileAsset.java`
- Test: `src/test/java/com/company/platform/file/domain/MultipartColumnsIT.java`

**Interfaces:**
- Produces:
  - `FileCategory#getMultipartPartSizeBytes() : Long` (nullable — null means multipart disabled for the category)
  - `FileAsset#getMultipartUploadId() : String` (nullable)
  - `FileAsset#attachMultipartUpload(String uploadId) : void`
  - `FileAsset#clearMultipartUpload() : void`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/platform/file/domain/MultipartColumnsIT.java`:

```java
package com.company.platform.file.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
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
class MultipartColumnsIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired FileCategoryRepository categoryRepository;
  @Autowired FileAssetRepository assetRepository;

  @Test
  void should_persistMultipartPartSize_when_setOnCategory() {
    String code = "MP_CAT_" + UUID.randomUUID().toString().substring(0, 8);
    categoryRepository.save(
        new FileCategory(code, "mp-bucket", List.of("text/csv"), 5_000_000_000L, false, false, null, 64L * 1024 * 1024));

    FileCategory reloaded = categoryRepository.findById(code).orElseThrow();
    assertThat(reloaded.getMultipartPartSizeBytes()).isEqualTo(64L * 1024 * 1024);
  }

  @Test
  void should_persistAndClearMultipartUploadId_when_setOnAsset() {
    FileAsset asset =
        new FileAsset(
            UUID.randomUUID(), "MP_CAT", "mp-bucket", UUID.randomUUID().toString(),
            "big.csv", "text/csv", 0L, "USER", UUID.randomUUID(), UUID.randomUUID(), null);
    asset.attachMultipartUpload("upload-abc-123");
    assetRepository.save(asset);

    assertThat(assetRepository.findById(asset.getId()).orElseThrow().getMultipartUploadId())
        .isEqualTo("upload-abc-123");

    FileAsset reloaded = assetRepository.findById(asset.getId()).orElseThrow();
    reloaded.clearMultipartUpload();
    assetRepository.save(reloaded);

    assertThat(assetRepository.findById(asset.getId()).orElseThrow().getMultipartUploadId()).isNull();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MultipartColumnsIT`
Expected: COMPILE FAIL — `FileCategory` has no 8-arg constructor, `FileAsset` has no `attachMultipartUpload`.

- [ ] **Step 3: Add the Liquibase changeset**

`src/main/resources/db/changelog/changes/011-init-multipart-upload.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 011-1-add-multipart-part-size-to-file-categories
      author: platform
      changes:
        - addColumn:
            tableName: file_categories
            columns:
              - column:
                  name: multipart_part_size_bytes
                  type: BIGINT
  - changeSet:
      id: 011-2-add-multipart-upload-id-to-file-assets
      author: platform
      changes:
        - addColumn:
            tableName: file_assets
            columns:
              - column:
                  name: multipart_upload_id
                  type: VARCHAR(255)
```

Add to `db.changelog-master.yaml` after the `010-` include:

```yaml
  - include:
      file: db/changelog/changes/011-init-multipart-upload.yaml
```

- [ ] **Step 4: Add the entity fields**

`FileCategory.java` — add field + constructor param (append last so existing seed/test callers break loudly and get updated):

```java
  @Column(name = "multipart_part_size_bytes")
  private Long multipartPartSizeBytes;
```

Add `Long multipartPartSizeBytes` as the final parameter of the public constructor and set `this.multipartPartSizeBytes = multipartPartSizeBytes;`.

`FileAsset.java` — add field + mutators (do NOT add to any constructor; it is set after creation):

```java
  @Column(name = "multipart_upload_id")
  private String multipartUploadId;

  public void attachMultipartUpload(String uploadId) {
    this.multipartUploadId = uploadId;
    this.updatedAt = Instant.now();
  }

  public void clearMultipartUpload() {
    this.multipartUploadId = null;
    this.updatedAt = Instant.now();
  }
```

- [ ] **Step 5: Fix existing `new FileCategory(...)` callers**

Run: `grep -rn "new FileCategory(" src`
Every call site (seed changesets are YAML, not Java — only Java test/main callers) gets `, null` appended as the last arg. Known Java callers: `FileUploadServiceIT`, `FileUploadControllerIT`, `AvatarUploadDownloadEndToEndIT`, `FileInternalDownloadEndToEndIT`, `FileTemplateServiceTest`, and any others the grep finds. For the seed rows in `006`/`007`/`008`/`010` YAML changesets, no change needed (new column is nullable, omitted = null).

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn spotless:apply && mvn test -Dtest=MultipartColumnsIT`
Expected: PASS. Then `mvn test -Dtest='FileUploadServiceIT,FileUploadControllerIT'` — PASS (regression check on the constructor change).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/changelog src/main/java/com/company/platform/file/domain docs/design/job-file-management.md docs/superpowers/plans src/test/java/com/company/platform/file/domain/MultipartColumnsIT.java
git add -A
git commit -m "$(cat <<'EOF'
feat(file): add multipart upload schema columns

- file_categories.multipart_part_size_bytes (nullable, null = multipart disabled)
- file_assets.multipart_upload_id (nullable, in-flight S3 uploadId)
- design doc section 8.1

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Task 2: AWS SDK v2 dependency + S3 client/presigner config

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/company/platform/file/storage/multipart/MultipartS3Config.java`
- Test: `src/test/java/com/company/platform/file/storage/multipart/MultipartS3ConfigIT.java`

**Interfaces:**
- Consumes: `MinioProperties` (`endpoint()`, `accessKey()`, `secretKey()`).
- Produces: Spring beans `S3Client s3MultipartClient` and `S3Presigner s3MultipartPresigner`, both pointed at the MinIO endpoint with path-style access and SDK default checksums disabled.

- [ ] **Step 1: Add dependencies to `pom.xml`**

Add a `<dependencyManagement>` block (the project currently has none) right before `<dependencies>`:

```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>bom</artifactId>
        <version>2.54.11</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
```

Add to `<dependencies>` (after the `minio` / `okhttp` block):

```xml
    <!-- MinIO Java SDK 9.0.0 không expose multipart lifecycle (Create/Complete/Abort/ListParts);
         dùng AWS SDK v2 S3 (MinIO S3-compatible) CHỈ cho luồng multipart, cô lập trong
         package file.storage.multipart. url-connection-client: HTTP client sync nhẹ, không native lib. -->
    <dependency>
      <groupId>software.amazon.awssdk</groupId>
      <artifactId>s3</artifactId>
    </dependency>
    <dependency>
      <groupId>software.amazon.awssdk</groupId>
      <artifactId>url-connection-client</artifactId>
    </dependency>
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/company/platform/file/storage/multipart/MultipartS3ConfigIT.java`:

```java
package com.company.platform.file.storage.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class MultipartS3ConfigIT {

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired S3Client s3MultipartClient;

  @Test
  void should_openAndAbortMultipartUpload_when_pointedAtMinio() throws Exception {
    String bucket = "cfg-it-" + UUID.randomUUID();
    MinioClient mc =
        MinioClient.builder()
            .endpoint(minio.getS3URL())
            .credentials(minio.getUserName(), minio.getPassword())
            .build();
    mc.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());

    String key = UUID.randomUUID().toString();
    String uploadId =
        s3MultipartClient
            .createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build())
            .uploadId();
    assertThat(uploadId).isNotBlank();

    s3MultipartClient.abortMultipartUpload(
        AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build());
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=MultipartS3ConfigIT`
Expected: FAIL — no `S3Client` bean.

- [ ] **Step 4: Write the config**

`src/main/java/com/company/platform/file/storage/multipart/MultipartS3Config.java`:

```java
package com.company.platform.file.storage.multipart;

import com.company.platform.file.storage.MinioProperties;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3Client/S3Presigner trỏ vào MinIO — CHỈ dùng cho luồng multipart (MinIO SDK 9.0.0 không expose
 * Create/Complete/Abort/ListParts). Dựng từ chính app.minio.* nên không thêm key config.
 *
 * <p>path-style bắt buộc (MinIO Docker không có vhost domain); region cố định (MinIO bỏ qua nhưng
 * SigV4 cần); tắt checksum mặc định của SDK v2 để không vỡ chữ ký presigned URL.
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MultipartS3Config {

  private static final Region SIGNING_REGION = Region.US_EAST_1;

  private static StaticCredentialsProvider credentials(MinioProperties p) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
  }

  @Bean
  public S3Client s3MultipartClient(MinioProperties properties) {
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(SIGNING_REGION)
        .credentialsProvider(credentials(properties))
        .forcePathStyle(true)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
  }

  @Bean
  public S3Presigner s3MultipartPresigner(MinioProperties properties) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(SIGNING_REGION)
        .credentialsProvider(credentials(properties))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
```

If `RequestChecksumCalculation` / `ResponseChecksumValidation` do not resolve, they live in `software.amazon.awssdk.core.checksums` in SDK 2.54.x — verify with `javap -cp <jar> software.amazon.awssdk.core.checksums.RequestChecksumCalculation`. If the builder methods are absent on this SDK version, drop those two lines; Task 3's IT will confirm whether presigned part PUTs still succeed (they must — that IT is the gate).

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn spotless:apply && mvn test -Dtest=MultipartS3ConfigIT`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/company/platform/file/storage/multipart/MultipartS3Config.java src/test/java/com/company/platform/file/storage/multipart/MultipartS3ConfigIT.java
git commit -m "$(cat <<'EOF'
feat(file): add isolated AWS SDK v2 S3 client for multipart

MinIO SDK 9.0.0 does not expose multipart lifecycle; add S3Client/S3Presigner
pointed at the same MinIO endpoint, confined to file.storage.multipart.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Task 3: MultipartStorageService (AWS SDK adapter)

**Files:**
- Create: `src/main/java/com/company/platform/file/storage/multipart/MultipartStorageService.java`
- Create: `src/main/java/com/company/platform/file/storage/multipart/MultipartStorageException.java`
- Create: `src/main/java/com/company/platform/file/storage/multipart/UploadedPart.java`
- Create: `src/main/java/com/company/platform/file/storage/multipart/CompletedPartRef.java`
- Test: `src/test/java/com/company/platform/file/storage/multipart/MultipartStorageServiceIT.java`

**Interfaces:**
- Consumes: `S3Client s3MultipartClient`, `S3Presigner s3MultipartPresigner` (Task 2).
- Produces:
  - `String createMultipartUpload(String bucket, String objectKey, String contentType)` → uploadId
  - `String presignUploadPart(String bucket, String objectKey, String uploadId, int partNumber, java.time.Duration expiry)` → URL string
  - `java.util.List<UploadedPart> listParts(String bucket, String objectKey, String uploadId)`
  - `void completeMultipartUpload(String bucket, String objectKey, String uploadId, java.util.List<CompletedPartRef> parts)`
  - `void abortMultipartUpload(String bucket, String objectKey, String uploadId)`
  - `record UploadedPart(int partNumber, String etag, long sizeBytes)`
  - `record CompletedPartRef(int partNumber, String etag)`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/platform/file/storage/multipart/MultipartStorageServiceIT.java`:

```java
package com.company.platform.file.storage.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Testcontainers
class MultipartStorageServiceIT {

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired MultipartStorageService storage;

  private MinioClient minioClient;
  private String bucket;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() throws Exception {
    minioClient =
        MinioClient.builder()
            .endpoint(minio.getS3URL())
            .credentials(minio.getUserName(), minio.getPassword())
            .build();
    bucket = "mp-store-" + UUID.randomUUID();
    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
  }

  private String putPart(String url, byte[] body) throws Exception {
    HttpResponse<Void> resp =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).PUT(BodyPublishers.ofByteArray(body)).build(),
            BodyHandlers.discarding());
    assertThat(resp.statusCode()).isEqualTo(200);
    return resp.headers().firstValue("ETag").orElseThrow();
  }

  @Test
  void should_completeMultipartUpload_when_allPartsUploaded() throws Exception {
    String key = UUID.randomUUID().toString();
    byte[] p1 = new byte[6 * 1024 * 1024]; // 6 MiB (min non-final part is 5 MiB)
    byte[] p2 = "tail".getBytes();

    String uploadId = storage.createMultipartUpload(bucket, key, "application/octet-stream");
    String e1 = putPart(storage.presignUploadPart(bucket, key, uploadId, 1, Duration.ofMinutes(10)), p1);
    String e2 = putPart(storage.presignUploadPart(bucket, key, uploadId, 2, Duration.ofMinutes(10)), p2);

    List<MultipartStorageService.UploadedPart> parts = storage.listParts(bucket, key, uploadId);
    assertThat(parts).extracting(MultipartStorageService.UploadedPart::partNumber).containsExactly(1, 2);

    storage.completeMultipartUpload(
        bucket, key, uploadId,
        List.of(
            new MultipartStorageService.CompletedPartRef(1, e1),
            new MultipartStorageService.CompletedPartRef(2, e2)));

    long size = minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();
    assertThat(size).isEqualTo((long) p1.length + p2.length);
  }

  @Test
  void should_removeUpload_when_aborted() {
    String key = UUID.randomUUID().toString();
    String uploadId = storage.createMultipartUpload(bucket, key, "application/octet-stream");

    storage.abortMultipartUpload(bucket, key, uploadId);

    assertThat(storage.listParts(bucket, key, uploadId)).isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MultipartStorageServiceIT`
Expected: FAIL — `MultipartStorageService` does not exist.

- [ ] **Step 3: Write `MultipartStorageException`**

```java
package com.company.platform.file.storage.multipart;

public class MultipartStorageException extends RuntimeException {
  public MultipartStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

- [ ] **Step 4: Write `MultipartStorageService`**

```java
package com.company.platform.file.storage.multipart;

import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/**
 * Adapter mỏng qua AWS SDK v2 cho luồng multipart. KHÔNG chứa business rule (auth, size limit,
 * state machine) — chỉ gọi storage. Chỉ class này (và MultipartS3Config) được import awssdk.
 */
@Service
public class MultipartStorageService {

  public record UploadedPart(int partNumber, String etag, long sizeBytes) {}

  public record CompletedPartRef(int partNumber, String etag) {}

  private final S3Client s3;
  private final S3Presigner presigner;

  public MultipartStorageService(S3Client s3MultipartClient, S3Presigner s3MultipartPresigner) {
    this.s3 = s3MultipartClient;
    this.presigner = s3MultipartPresigner;
  }

  public String createMultipartUpload(String bucket, String objectKey, String contentType) {
    try {
      return s3.createMultipartUpload(
              CreateMultipartUploadRequest.builder()
                  .bucket(bucket)
                  .key(objectKey)
                  .contentType(contentType)
                  .build())
          .uploadId();
    } catch (S3Exception e) {
      throw new MultipartStorageException(
          "Không mở được multipart upload cho %s/%s".formatted(bucket, objectKey), e);
    }
  }

  public String presignUploadPart(
      String bucket, String objectKey, String uploadId, int partNumber, Duration expiry) {
    UploadPartRequest part =
        UploadPartRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .uploadId(uploadId)
            .partNumber(partNumber)
            .build();
    return presigner
        .presignUploadPart(
            UploadPartPresignRequest.builder()
                .signatureDuration(expiry)
                .uploadPartRequest(part)
                .build())
        .url()
        .toString();
  }

  public List<UploadedPart> listParts(String bucket, String objectKey, String uploadId) {
    try {
      return s3
          .listPartsPaginator(
              ListPartsRequest.builder().bucket(bucket).key(objectKey).uploadId(uploadId).build())
          .parts()
          .stream()
          .map(p -> new UploadedPart(p.partNumber(), p.eTag(), p.size()))
          .toList();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return List.of();
      }
      throw new MultipartStorageException(
          "Không liệt kê được part của %s/%s".formatted(bucket, objectKey), e);
    }
  }

  public void completeMultipartUpload(
      String bucket, String objectKey, String uploadId, List<CompletedPartRef> parts) {
    List<CompletedPart> completed =
        parts.stream()
            .sorted(java.util.Comparator.comparingInt(CompletedPartRef::partNumber))
            .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.etag()).build())
            .toList();
    try {
      s3.completeMultipartUpload(
          CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .uploadId(uploadId)
              .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
              .build());
    } catch (S3Exception e) {
      throw new MultipartStorageException(
          "Không complete được multipart upload %s/%s".formatted(bucket, objectKey), e);
    }
  }

  public void abortMultipartUpload(String bucket, String objectKey, String uploadId) {
    try {
      s3.abortMultipartUpload(
          AbortMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .uploadId(uploadId)
              .build());
    } catch (S3Exception e) {
      throw new MultipartStorageException(
          "Không abort được multipart upload %s/%s".formatted(bucket, objectKey), e);
    }
  }
}
```

Delete the standalone `UploadedPart.java` / `CompletedPartRef.java` files from the File Structure list — they are nested records inside `MultipartStorageService` (matches the test's `MultipartStorageService.UploadedPart` references).

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn spotless:apply && mvn test -Dtest=MultipartStorageServiceIT`
Expected: PASS. If a presigned part PUT returns 403 SignatureDoesNotMatch, the checksum knob is the cause — see Task 2 Step 4 note; try adding `.putObject`-side `chunkedEncodingEnabled(false)` to the presigner's `S3Configuration`, then re-run.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/platform/file/storage/multipart src/test/java/com/company/platform/file/storage/multipart/MultipartStorageServiceIT.java
git commit -m "$(cat <<'EOF'
feat(file): add MultipartStorageService adapter over AWS SDK v2

create / presign-part / listParts / complete / abort against MinIO.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Task 4: Extract FileAssetStatusFinalizer, refactor confirmUpload

**Files:**
- Create: `src/main/java/com/company/platform/file/application/FileAssetStatusFinalizer.java`
- Modify: `src/main/java/com/company/platform/file/application/FileUploadService.java`
- Test: `src/test/java/com/company/platform/file/application/FileAssetStatusFinalizerTest.java` (unit, mocked repo + storage) — plus the existing `FileUploadServiceIT` is the regression gate.

**Interfaces:**
- Consumes: `FileAssetRepository`, `MinioStorageService`.
- Produces: `FileAssetStatusFinalizer#finalizeToClean(UUID fileAssetId, String checksumSha256) : FileAsset` — the exact behaviour currently in `FileUploadService.confirmUpload` (idempotent-if-CLEAN; CAS `UPLOADING→UPLOADED_DMZ` when still UPLOADING; HEAD verify existence + size; CAS `UPLOADED_DMZ→CLEAN` + checksum; throw `InvalidFileAssetStateException` / `FileVerificationFailedException`).

- [ ] **Step 1: Write the failing test**

`FileAssetStatusFinalizerTest.java`:

```java
package com.company.platform.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import io.minio.StatObjectResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileAssetStatusFinalizerTest {

  private final FileAssetRepository repo = mock(FileAssetRepository.class);
  private final MinioStorageService storage = mock(MinioStorageService.class);
  private final FileAssetStatusFinalizer finalizer = new FileAssetStatusFinalizer(repo, storage);

  @Test
  void should_returnAssetUnchanged_when_alreadyClean() {
    UUID id = UUID.randomUUID();
    FileAsset asset = cleanAsset(id);
    when(repo.findById(id)).thenReturn(Optional.of(asset));

    assertThat(finalizer.finalizeToClean(id, "abc")).isSameAs(asset);
  }

  // Additional cases (UPLOADING happy path, size mismatch -> FileVerificationFailedException,
  // failed second CAS -> InvalidFileAssetStateException) mirror the assertions already covered by
  // FileUploadServiceIT; keep this unit test focused on the idempotent branch and one failure
  // branch, and rely on FileUploadServiceIT for the DB-backed CAS behaviour.

  private FileAsset cleanAsset(UUID id) {
    FileAsset a =
        new FileAsset(id, "C", "b", "k", "f", "m", 3L, "USER", UUID.randomUUID(), UUID.randomUUID(), null);
    // reflectively/though a test helper set storageStatus = CLEAN, or add a package-private setter
    // in a test-support subclass. Simplest: use the real transition via repo stubs is overkill here
    // — instead assert against a spy. If FileAsset has no way to reach CLEAN in-memory, drop this
    // unit test and rely entirely on FileUploadServiceIT (acceptable: the refactor is behaviour-
    // preserving and IT-covered).
    return a;
  }
}
```

> Note for the implementer: `FileAsset` has no in-memory path to `storageStatus = CLEAN` (only CAS repo updates set it). If mocking becomes contorted, **delete this unit test** and treat `FileUploadServiceIT` (which already exercises `confirmUpload` end-to-end against real Postgres + MinIO) as the sole gate for this pure refactor. Do not add production setters just to enable a mock.

- [ ] **Step 2: Run the regression gate to capture current green**

Run: `mvn test -Dtest=FileUploadServiceIT`
Expected: PASS (baseline before refactor).

- [ ] **Step 3: Create `FileAssetStatusFinalizer` by moving code out of `FileUploadService`**

`FileAssetStatusFinalizer.java` — move `confirmUpload`'s body and the private `verifyUploadedObject` here verbatim:

```java
package com.company.platform.file.application;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileVerificationFailedException;
import com.company.platform.file.domain.InvalidFileAssetStateException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import io.minio.StatObjectResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Verify + CAS về CLEAN" dùng chung cho confirm luồng presigned PUT đơn (FileUploadService) và
 * complete luồng multipart (MultipartUploadService) — cùng chuỗi UPLOADING -> UPLOADED_DMZ -> CLEAN
 * (mục 5 design doc), idempotent nếu đã CLEAN.
 */
@Component
public class FileAssetStatusFinalizer {

  private final FileAssetRepository fileAssetRepository;
  private final MinioStorageService minioStorageService;

  public FileAssetStatusFinalizer(
      FileAssetRepository fileAssetRepository, MinioStorageService minioStorageService) {
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
  }

  @Transactional
  public FileAsset finalizeToClean(UUID fileAssetId, String checksumSha256) {
    // ... exact body previously in FileUploadService.confirmUpload ...
  }

  private void verifyUploadedObject(FileAsset fileAsset) {
    // ... exact body previously in FileUploadService.verifyUploadedObject ...
  }
}
```

- [ ] **Step 4: Rewrite `FileUploadService.confirmUpload` to delegate**

```java
  private final FileAssetStatusFinalizer fileAssetStatusFinalizer;
  // add to constructor, keep the other injected fields

  @Transactional
  public FileAsset confirmUpload(UUID fileAssetId, String checksumSha256) {
    return fileAssetStatusFinalizer.finalizeToClean(fileAssetId, checksumSha256);
  }
```

Leave `registerServerGeneratedFile` untouched — its pre-verify CAS path differs and is covered by export ITs; do not refactor it in this task.

- [ ] **Step 5: Run tests**

Run: `mvn spotless:apply && mvn test -Dtest='FileUploadServiceIT,FileUploadControllerTest,FileUploadControllerIT'`
Expected: PASS (behaviour preserved). Run `FileAssetStatusFinalizerTest` if you kept it.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/platform/file/application src/test/java/com/company/platform/file/application
git commit -m "$(cat <<'EOF'
refactor(file): extract FileAssetStatusFinalizer from confirmUpload

Shared verify + CAS-to-CLEAN for the upcoming multipart complete path.
Behaviour-preserving; FileUploadServiceIT unchanged and green.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Task 5: MultipartUploadService — initiate + generatePartUrls

**Files:**
- Create: `src/main/java/com/company/platform/file/application/MultipartUploadService.java`
- Create: `src/main/java/com/company/platform/file/application/InitiateMultipartUploadCommand.java`
- Create: `src/main/java/com/company/platform/file/application/MultipartUploadInitiation.java`
- Create: `src/main/java/com/company/platform/file/application/PartUrl.java` — record `(int partNumber, String url, java.time.Instant expiresAt)`
- Create: `src/main/java/com/company/platform/file/domain/MultipartNotEnabledException.java`
- Create: `src/main/java/com/company/platform/file/domain/InvalidPartNumberException.java`
- Modify: `src/main/java/com/company/platform/file/domain/FileAssetRepository.java` — add `clearMultipartUploadId`, `updateSizeBytes`.
- Test: `src/test/java/com/company/platform/file/application/MultipartUploadServiceIT.java`

**Interfaces:**
- Consumes: `FileCategoryRepository`, `FileAssetRepository`, `MinioStorageService` (`ensureBucketExists`), `MultipartStorageService`, `FileAssetStatusFinalizer` (Task 4, used in Task 6).
- Produces:
  - `MultipartUploadInitiation initiateMultipart(InitiateMultipartUploadCommand cmd)` — record `InitiateMultipartUploadCommand(String categoryCode, String originalFilename, String mimeType, String ownerType, UUID ownerId, UUID uploadedBy)`
  - `MultipartUploadInitiation(UUID fileAssetId, long partSizeBytes, int maxParts)`
  - `List<PartUrl> generatePartUrls(UUID fileAssetId, List<Integer> partNumbers)`
  - Constants: `PART_URL_EXPIRY = Duration.ofMinutes(30)`, `MAX_S3_PARTS = 10_000`
- Repo additions:
  - `int clearMultipartUploadId(UUID id, Instant now)` — `@Modifying`, `SET f.multipartUploadId = null, f.updatedAt = :now WHERE f.id = :id`
  - `int updateSizeBytes(UUID id, long sizeBytes, Instant now)` — `@Modifying`, `SET f.sizeBytes = :sizeBytes, f.updatedAt = :now WHERE f.id = :id AND f.storageStatus = com.company.platform.file.domain.StorageStatus.UPLOADING`

- [ ] **Step 1: Write the failing test**

`MultipartUploadServiceIT.java`:

```java
package com.company.platform.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.InvalidPartNumberException;
import com.company.platform.file.domain.MultipartNotEnabledException;
import com.company.platform.file.domain.StorageStatus;
import java.util.List;
import java.util.UUID;
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
class MultipartUploadServiceIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("app.minio.endpoint", minio::getS3URL);
    registry.add("app.minio.access-key", minio::getUserName);
    registry.add("app.minio.secret-key", minio::getPassword);
  }

  @Autowired MultipartUploadService service;
  @Autowired FileCategoryRepository categoryRepository;
  @Autowired FileAssetRepository assetRepository;

  private String mpCategory;
  private String plainCategory;

  @BeforeEach
  void seed() {
    String s = UUID.randomUUID().toString().substring(0, 8);
    mpCategory = "MP_" + s;
    plainCategory = "PLAIN_" + s;
    categoryRepository.save(
        new FileCategory(mpCategory, "mp-" + s, List.of("text/csv"), 500L * 1024 * 1024, false, false, null, 64L * 1024 * 1024));
    categoryRepository.save(
        new FileCategory(plainCategory, "plain-" + s, List.of("text/csv"), 1_000_000L, false, false, null, null));
  }

  private InitiateMultipartUploadCommand cmd(String category) {
    UUID user = UUID.randomUUID();
    return new InitiateMultipartUploadCommand(category, "big.csv", "text/csv", "USER", user, user);
  }

  @Test
  void should_createUploadingAssetWithUploadId_when_initiate() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    FileAsset asset = assetRepository.findById(init.fileAssetId()).orElseThrow();
    assertThat(asset.getStorageStatus()).isEqualTo(StorageStatus.UPLOADING);
    assertThat(asset.getMultipartUploadId()).isNotBlank();
    assertThat(init.partSizeBytes()).isEqualTo(64L * 1024 * 1024);
    assertThat(init.maxParts()).isEqualTo(8); // ceil(500MiB / 64MiB)
  }

  @Test
  void should_reject_when_categoryHasNoPartSize() {
    assertThatThrownBy(() -> service.initiateMultipart(cmd(plainCategory)))
        .isInstanceOf(MultipartNotEnabledException.class);
  }

  @Test
  void should_returnOnePresignedUrlPerRequestedPart_when_generatePartUrls() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    List<PartUrl> urls = service.generatePartUrls(init.fileAssetId(), List.of(1, 2, 3));

    assertThat(urls).extracting(PartUrl::partNumber).containsExactly(1, 2, 3);
    assertThat(urls).allSatisfy(u -> assertThat(u.url()).startsWith("http"));
  }

  @Test
  void should_reject_when_partNumberBeyondMaxParts() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    assertThatThrownBy(() -> service.generatePartUrls(init.fileAssetId(), List.of(9)))
        .isInstanceOf(InvalidPartNumberException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MultipartUploadServiceIT`
Expected: FAIL — types don't exist.

- [ ] **Step 3: Create the records + exceptions**

```java
// InitiateMultipartUploadCommand.java
package com.company.platform.file.application;
import java.util.UUID;
public record InitiateMultipartUploadCommand(
    String categoryCode, String originalFilename, String mimeType,
    String ownerType, UUID ownerId, UUID uploadedBy) {}
```

```java
// MultipartUploadInitiation.java
package com.company.platform.file.application;
import java.util.UUID;
public record MultipartUploadInitiation(UUID fileAssetId, long partSizeBytes, int maxParts) {}
```

```java
// PartUrl.java
package com.company.platform.file.application;
import java.time.Instant;
public record PartUrl(int partNumber, String url, Instant expiresAt) {}
```

```java
// MultipartNotEnabledException.java
package com.company.platform.file.domain;
public class MultipartNotEnabledException extends RuntimeException {
  public MultipartNotEnabledException(String categoryCode) {
    super("File category %s không bật multipart upload (thiếu multipart_part_size_bytes)".formatted(categoryCode));
  }
}
```

```java
// InvalidPartNumberException.java
package com.company.platform.file.domain;
public class InvalidPartNumberException extends RuntimeException {
  public InvalidPartNumberException(int partNumber, int maxParts) {
    super("Part number %d không hợp lệ (phải trong 1..%d)".formatted(partNumber, maxParts));
  }
}
```

- [ ] **Step 4: Add repo methods**

In `FileAssetRepository`:

```java
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE FileAsset f SET f.multipartUploadId = null, f.updatedAt = :now WHERE f.id = :id")
  int clearMultipartUploadId(@Param("id") UUID id, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE FileAsset f SET f.sizeBytes = :sizeBytes, f.updatedAt = :now "
          + "WHERE f.id = :id AND f.storageStatus = com.company.platform.file.domain.StorageStatus.UPLOADING")
  int updateSizeBytes(
      @Param("id") UUID id, @Param("sizeBytes") long sizeBytes, @Param("now") Instant now);
```

- [ ] **Step 5: Write `MultipartUploadService` (initiate + generatePartUrls only)**

```java
package com.company.platform.file.application;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryNotFoundException;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.InvalidPartNumberException;
import com.company.platform.file.domain.MultipartNotEnabledException;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.file.storage.multipart.MultipartStorageService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MultipartUploadService {

  static final Duration PART_URL_EXPIRY = Duration.ofMinutes(30);
  static final int MAX_S3_PARTS = 10_000;

  private final FileCategoryRepository fileCategoryRepository;
  private final FileAssetRepository fileAssetRepository;
  private final MinioStorageService minioStorageService;
  private final MultipartStorageService multipartStorageService;

  public MultipartUploadService(
      FileCategoryRepository fileCategoryRepository,
      FileAssetRepository fileAssetRepository,
      MinioStorageService minioStorageService,
      MultipartStorageService multipartStorageService) {
    this.fileCategoryRepository = fileCategoryRepository;
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
    this.multipartStorageService = multipartStorageService;
  }

  @Transactional
  public MultipartUploadInitiation initiateMultipart(InitiateMultipartUploadCommand command) {
    FileCategory category =
        fileCategoryRepository
            .findById(command.categoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(command.categoryCode()));

    Long partSize = category.getMultipartPartSizeBytes();
    if (partSize == null) {
      throw new MultipartNotEnabledException(category.getCode());
    }
    if (!category.getAllowedMimeTypes().contains(command.mimeType())) {
      throw new FileValidationException(
          "Mime type %s không được phép cho category %s"
              .formatted(command.mimeType(), command.categoryCode()));
    }

    minioStorageService.ensureBucketExists(category.getBucketName(), category.getRetentionDays());

    UUID fileAssetId = UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();

    FileAsset fileAsset =
        new FileAsset(
            fileAssetId,
            category.getCode(),
            category.getBucketName(),
            objectKey,
            command.originalFilename(),
            command.mimeType(),
            0L,
            command.ownerType(),
            command.ownerId(),
            command.uploadedBy(),
            computeExpiresAt(category));

    String uploadId =
        multipartStorageService.createMultipartUpload(
            category.getBucketName(), objectKey, command.mimeType());
    fileAsset.attachMultipartUpload(uploadId);
    fileAssetRepository.save(fileAsset);

    return new MultipartUploadInitiation(fileAssetId, partSize, maxParts(category, partSize));
  }

  @Transactional(readOnly = true)
  public List<PartUrl> generatePartUrls(UUID fileAssetId, List<Integer> partNumbers) {
    FileAsset asset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
    FileCategory category =
        fileCategoryRepository
            .findById(asset.getCategoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(asset.getCategoryCode()));
    Long partSize = category.getMultipartPartSizeBytes();
    if (partSize == null || asset.getMultipartUploadId() == null) {
      throw new MultipartNotEnabledException(asset.getCategoryCode());
    }
    int max = maxParts(category, partSize);

    Instant expiresAt = Instant.now().plus(PART_URL_EXPIRY);
    return partNumbers.stream()
        .map(
            n -> {
              if (n < 1 || n > max) {
                throw new InvalidPartNumberException(n, max);
              }
              String url =
                  multipartStorageService.presignUploadPart(
                      asset.getBucketName(),
                      asset.getObjectKey(),
                      asset.getMultipartUploadId(),
                      n,
                      PART_URL_EXPIRY);
              return new PartUrl(n, url, expiresAt);
            })
        .toList();
  }

  private int maxParts(FileCategory category, long partSize) {
    long ceil = (category.getMaxSizeBytes() + partSize - 1) / partSize;
    return (int) Math.min(ceil, MAX_S3_PARTS);
  }

  private Instant computeExpiresAt(FileCategory category) {
    Integer retentionDays = category.getRetentionDays();
    return retentionDays == null ? null : Instant.now().plus(Duration.ofDays(retentionDays));
  }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn spotless:apply && mvn test -Dtest=MultipartUploadServiceIT`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/platform/file src/test/java/com/company/platform/file/application/MultipartUploadServiceIT.java
git commit -m "$(cat <<'EOF'
feat(file): multipart initiate + presigned part URLs

MultipartUploadService.initiateMultipart / generatePartUrls, backend-decided
part size, part-number bounds check against maxParts.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Task 6: MultipartUploadService — listUploadedParts + complete + abort

**Files:**
- Modify: `src/main/java/com/company/platform/file/application/MultipartUploadService.java`
- Modify: `src/main/java/com/company/platform/file/domain/FileAssetRepository.java` — add `softDelete`.
- Create: `src/main/java/com/company/platform/file/application/CompleteMultipartUploadCommand.java` — record `(UUID fileAssetId, List<PartRef> parts, String checksumSha256)`, nested `record PartRef(int partNumber, String etag)`.
- Test: extend `src/test/java/com/company/platform/file/application/MultipartUploadServiceIT.java`.

**Interfaces:**
- Consumes: `FileAssetStatusFinalizer` (Task 4), `MultipartStorageService`, `MinioStorageService.statObject`.
- Produces:
  - `List<MultipartStorageService.UploadedPart> listUploadedParts(UUID fileAssetId)`
  - `FileAsset completeMultipart(CompleteMultipartUploadCommand cmd)` — completes the S3 upload, stat-checks real size against `category.maxSizeBytes` (abort + `FileVerificationFailedException` if exceeded), `updateSizeBytes`, `finalizer.finalizeToClean`, `clearMultipartUploadId`. Idempotent if already CLEAN.
  - `void abortMultipart(UUID fileAssetId)` — S3 abort + `softDelete` + `clearMultipartUploadId`. Idempotent if uploadId already null.
- Repo addition:
  - `int softDelete(UUID id, Instant now)` — `@Modifying`, `SET f.deletedAt = :now, f.updatedAt = :now WHERE f.id = :id AND f.deletedAt IS NULL`

- [ ] **Step 1: Write the failing tests (append to `MultipartUploadServiceIT`)**

```java
  @Autowired com.company.platform.file.storage.multipart.MultipartStorageService storage;

  private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

  private String putPart(String url, byte[] body) throws Exception {
    var resp =
        http.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.discarding());
    assertThat(resp.statusCode()).isEqualTo(200);
    return resp.headers().firstValue("ETag").orElseThrow();
  }

  @Test
  void should_transitionToClean_when_completeAfterUploadingParts() throws Exception {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));
    byte[] p1 = new byte[6 * 1024 * 1024];
    byte[] p2 = "tail".getBytes();
    List<PartUrl> urls = service.generatePartUrls(init.fileAssetId(), List.of(1, 2));
    String e1 = putPart(urls.get(0).url(), p1);
    String e2 = putPart(urls.get(1).url(), p2);

    FileAsset done =
        service.completeMultipart(
            new CompleteMultipartUploadCommand(
                init.fileAssetId(),
                List.of(
                    new CompleteMultipartUploadCommand.PartRef(1, e1),
                    new CompleteMultipartUploadCommand.PartRef(2, e2)),
                "cafe"));

    assertThat(done.getStorageStatus()).isEqualTo(StorageStatus.CLEAN);
    assertThat(done.getSizeBytes()).isEqualTo((long) p1.length + p2.length);
    assertThat(done.getChecksumSha256()).isEqualTo("cafe");
    assertThat(assetRepository.findById(init.fileAssetId()).orElseThrow().getMultipartUploadId()).isNull();
  }

  @Test
  void should_listUploadedParts_when_someUploaded() throws Exception {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));
    putPart(service.generatePartUrls(init.fileAssetId(), List.of(1)).get(0).url(), new byte[6 * 1024 * 1024]);

    assertThat(service.listUploadedParts(init.fileAssetId()))
        .extracting(MultipartStorageService.UploadedPart::partNumber)
        .containsExactly(1);
  }

  @Test
  void should_softDeleteAsset_when_abort() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    service.abortMultipart(init.fileAssetId());

    FileAsset asset = assetRepository.findById(init.fileAssetId()).orElseThrow();
    assertThat(asset.getDeletedAt()).isNotNull();
    assertThat(asset.getMultipartUploadId()).isNull();
  }
```

For an oversize test, seed a category with `maxSizeBytes = 1_000` and `multipartPartSizeBytes = 6 MiB`, upload one 6 MiB part, and assert `completeMultipart` throws `FileVerificationFailedException` and the S3 upload is aborted (`listUploadedParts` — expect it to surface as empty or the asset soft-deleted per your choice; assert the asset did NOT reach CLEAN).

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -Dtest=MultipartUploadServiceIT`
Expected: FAIL — `completeMultipart` / `CompleteMultipartUploadCommand` missing.

- [ ] **Step 3: Add `softDelete` repo method + `CompleteMultipartUploadCommand`**

```java
// FileAssetRepository
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE FileAsset f SET f.deletedAt = :now, f.updatedAt = :now "
          + "WHERE f.id = :id AND f.deletedAt IS NULL")
  int softDelete(@Param("id") UUID id, @Param("now") Instant now);
```

```java
// CompleteMultipartUploadCommand.java
package com.company.platform.file.application;
import java.util.List;
import java.util.UUID;
public record CompleteMultipartUploadCommand(UUID fileAssetId, List<PartRef> parts, String checksumSha256) {
  public record PartRef(int partNumber, String etag) {}
}
```

- [ ] **Step 4: Implement the 3 methods in `MultipartUploadService`**

```java
  private final FileAssetStatusFinalizer fileAssetStatusFinalizer;
  // add to constructor

  @Transactional(readOnly = true)
  public List<MultipartStorageService.UploadedPart> listUploadedParts(UUID fileAssetId) {
    FileAsset asset = requireOpenMultipart(fileAssetId);
    return multipartStorageService.listParts(
        asset.getBucketName(), asset.getObjectKey(), asset.getMultipartUploadId());
  }

  @Transactional
  public FileAsset completeMultipart(CompleteMultipartUploadCommand cmd) {
    FileAsset asset =
        fileAssetRepository
            .findById(cmd.fileAssetId())
            .orElseThrow(() -> new FileAssetNotFoundException(cmd.fileAssetId()));
    if (asset.getStorageStatus() == StorageStatus.CLEAN) {
      return asset; // idempotent
    }
    String uploadId = asset.getMultipartUploadId();
    if (uploadId == null) {
      throw new InvalidFileAssetStateException(cmd.fileAssetId(), asset.getStorageStatus());
    }
    FileCategory category =
        fileCategoryRepository
            .findById(asset.getCategoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(asset.getCategoryCode()));

    multipartStorageService.completeMultipartUpload(
        asset.getBucketName(),
        asset.getObjectKey(),
        uploadId,
        cmd.parts().stream()
            .map(p -> new MultipartStorageService.CompletedPartRef(p.partNumber(), p.etag()))
            .toList());

    long realSize =
        minioStorageService
            .statObject(asset.getBucketName(), asset.getObjectKey())
            .orElseThrow(
                () ->
                    new FileVerificationFailedException(
                        asset.getId(), "object không tồn tại sau complete"))
            .size();
    if (realSize > category.getMaxSizeBytes()) {
      multipartStorageService.abortMultipartUpload(
          asset.getBucketName(), asset.getObjectKey(), uploadId);
      fileAssetRepository.softDelete(asset.getId(), Instant.now());
      fileAssetRepository.clearMultipartUploadId(asset.getId(), Instant.now());
      throw new FileVerificationFailedException(
          asset.getId(),
          "kích thước %d vượt trần %d của category".formatted(realSize, category.getMaxSizeBytes()));
    }

    fileAssetRepository.updateSizeBytes(asset.getId(), realSize, Instant.now());
    FileAsset clean =
        fileAssetStatusFinalizer.finalizeToClean(asset.getId(), cmd.checksumSha256());
    fileAssetRepository.clearMultipartUploadId(asset.getId(), Instant.now());
    return clean;
  }

  @Transactional
  public void abortMultipart(UUID fileAssetId) {
    FileAsset asset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
    String uploadId = asset.getMultipartUploadId();
    if (uploadId != null) {
      multipartStorageService.abortMultipartUpload(
          asset.getBucketName(), asset.getObjectKey(), uploadId);
    }
    fileAssetRepository.softDelete(fileAssetId, Instant.now());
    fileAssetRepository.clearMultipartUploadId(fileAssetId, Instant.now());
  }

  private FileAsset requireOpenMultipart(UUID fileAssetId) {
    FileAsset asset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
    if (asset.getMultipartUploadId() == null) {
      throw new InvalidFileAssetStateException(fileAssetId, asset.getStorageStatus());
    }
    return asset;
  }
```

Add imports: `InvalidFileAssetStateException`, `FileVerificationFailedException`, `StorageStatus`, `FileAssetStatusFinalizer`.

Note the `MinioStorageService.statObject` signature is `Optional<StatObjectResponse> statObject(String bucket, String objectKey)` — already exists.

- [ ] **Step 5: Run to verify pass**

Run: `mvn spotless:apply && mvn test -Dtest=MultipartUploadServiceIT`
Expected: PASS (all cases incl. oversize + abort).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/platform/file src/test/java/com/company/platform/file/application/MultipartUploadServiceIT.java
git commit -m "$(cat <<'EOF'
feat(file): multipart complete / listParts / abort

Complete verifies real object size against category max (abort + soft-delete on
overflow), reuses FileAssetStatusFinalizer for the CAS-to-CLEAN, clears uploadId.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Task 7: REST controller + DTOs + exception mapping

**Files:**
- Create: `src/main/java/com/company/platform/file/api/FileMultipartUploadController.java`
- Create: `InitiateMultipartUploadRequest.java`, `InitiateMultipartUploadResponse.java`, `PartUrlsRequest.java`, `PartUrlsResponse.java`, `UploadedPartsResponse.java`, `CompleteMultipartUploadRequest.java` (all in `file/api`)
- Modify: `src/main/java/com/company/platform/file/api/FileApiExceptionHandler.java`
- Test: `src/test/java/com/company/platform/file/api/FileMultipartUploadControllerTest.java` (`@WebMvcTest`-style slice with mocked `MultipartUploadService` + `UserContext`, mirroring `FileUploadControllerTest`).

**Interfaces:**
- Consumes: `MultipartUploadService`, `UserContext`.
- Endpoints (all `/api/files/multipart`, JSON):
  - `POST ""` → `initiate` → 200 `InitiateMultipartUploadResponse(UUID fileAssetId, long partSizeBytes, int maxParts)`. No `@RequiresAccess` (no resource yet — mirrors `initiateUpload`).
  - `POST "/{fileAssetId}/part-urls"` `@RequiresAccess(FILE_ASSET, WRITE)` → body `PartUrlsRequest(List<Integer> partNumbers)` → `PartUrlsResponse(List<PartUrlView> urls)`, `PartUrlView(int partNumber, String url, Instant expiresAt)`.
  - `GET "/{fileAssetId}/parts"` `@RequiresAccess(FILE_ASSET, WRITE)` → `UploadedPartsResponse(List<UploadedPartView> parts)`, `UploadedPartView(int partNumber, String etag, long sizeBytes)`.
  - `POST "/{fileAssetId}/complete"` `@RequiresAccess(FILE_ASSET, WRITE)` → body `CompleteMultipartUploadRequest(List<PartInput> parts, String checksumSha256)`, `PartInput(@Positive int partNumber, @NotBlank String etag)` → `FileAssetResponse` (reuse existing).
  - `POST "/{fileAssetId}/abort"` `@RequiresAccess(FILE_ASSET, WRITE)` → 204.

- [ ] **Step 1: Write the failing slice test**

`FileMultipartUploadControllerTest.java` — follow `FileUploadControllerTest`'s exact setup (same `@WebMvcTest` variant, `@MockitoBean MultipartUploadService`, `@MockitoBean UserContext`; the slice does NOT run the auth aspect, so assert plumbing only):

```java
  @Test
  void should_return200WithPartSize_when_initiate() throws Exception {
    when(multipartUploadService.initiateMultipart(any()))
        .thenReturn(new MultipartUploadInitiation(FILE_ASSET_ID, 64L * 1024 * 1024, 8));

    mockMvc
        .perform(
            post("/api/files/multipart")
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"categoryCode\":\"IMPORT_RAW\",\"originalFilename\":\"big.csv\","
                        + "\"mimeType\":\"text/csv\",\"ownerType\":\"USER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.partSizeBytes").value(67108864))
        .andExpect(jsonPath("$.maxParts").value(8));
  }

  @Test
  void should_return200WithUrls_when_requestingPartUrls() throws Exception {
    when(multipartUploadService.generatePartUrls(eq(FILE_ASSET_ID), any()))
        .thenReturn(List.of(new PartUrl(1, "http://minio/part1", Instant.parse("2026-09-03T10:00:00Z"))));

    mockMvc
        .perform(
            post("/api/files/multipart/" + FILE_ASSET_ID + "/part-urls")
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"partNumbers\":[1]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.urls[0].partNumber").value(1))
        .andExpect(jsonPath("$.urls[0].url").value("http://minio/part1"));
  }

  @Test
  void should_return204_when_abort() throws Exception {
    mockMvc
        .perform(
            post("/api/files/multipart/" + FILE_ASSET_ID + "/abort")
                .header("X-User-Id", USER_ID.toString()))
        .andExpect(status().isNoContent());
  }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -Dtest=FileMultipartUploadControllerTest`
Expected: FAIL — controller/DTOs missing.

- [ ] **Step 3: Write the DTO records**

```java
// InitiateMultipartUploadRequest.java
package com.company.platform.file.api;
import jakarta.validation.constraints.NotBlank;
public record InitiateMultipartUploadRequest(
    @NotBlank String categoryCode,
    @NotBlank String originalFilename,
    @NotBlank String mimeType,
    @NotBlank String ownerType) {}
```

```java
// InitiateMultipartUploadResponse.java
package com.company.platform.file.api;
import com.company.platform.file.application.MultipartUploadInitiation;
public record InitiateMultipartUploadResponse(java.util.UUID fileAssetId, long partSizeBytes, int maxParts) {
  public static InitiateMultipartUploadResponse from(MultipartUploadInitiation i) {
    return new InitiateMultipartUploadResponse(i.fileAssetId(), i.partSizeBytes(), i.maxParts());
  }
}
```

```java
// PartUrlsRequest.java
package com.company.platform.file.api;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
public record PartUrlsRequest(@NotEmpty List<Integer> partNumbers) {}
```

```java
// PartUrlsResponse.java
package com.company.platform.file.api;
import com.company.platform.file.application.PartUrl;
import java.time.Instant;
import java.util.List;
public record PartUrlsResponse(List<PartUrlView> urls) {
  public record PartUrlView(int partNumber, String url, Instant expiresAt) {}
  public static PartUrlsResponse from(List<PartUrl> urls) {
    return new PartUrlsResponse(
        urls.stream().map(u -> new PartUrlView(u.partNumber(), u.url(), u.expiresAt())).toList());
  }
}
```

```java
// UploadedPartsResponse.java
package com.company.platform.file.api;
import com.company.platform.file.storage.multipart.MultipartStorageService.UploadedPart;
import java.util.List;
public record UploadedPartsResponse(List<UploadedPartView> parts) {
  public record UploadedPartView(int partNumber, String etag, long sizeBytes) {}
  public static UploadedPartsResponse from(List<UploadedPart> parts) {
    return new UploadedPartsResponse(
        parts.stream()
            .map(p -> new UploadedPartView(p.partNumber(), p.etag(), p.sizeBytes()))
            .toList());
  }
}
```

```java
// CompleteMultipartUploadRequest.java
package com.company.platform.file.api;
import com.company.platform.file.application.CompleteMultipartUploadCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
public record CompleteMultipartUploadRequest(
    @NotEmpty @Valid List<PartInput> parts, @NotBlank String checksumSha256) {
  public record PartInput(@Positive int partNumber, @NotBlank String etag) {}
  public CompleteMultipartUploadCommand toCommand(UUID fileAssetId) {
    return new CompleteMultipartUploadCommand(
        fileAssetId,
        parts.stream()
            .map(p -> new CompleteMultipartUploadCommand.PartRef(p.partNumber(), p.etag()))
            .toList(),
        checksumSha256);
  }
}
```

- [ ] **Step 4: Write the controller** (add full `@Operation`/`@ApiResponse` Swagger annotations matching the density of `FileUploadController`)

```java
package com.company.platform.file.api;

import com.company.platform.file.application.MultipartUploadService;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.RequiresAccess;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files/multipart")
public class FileMultipartUploadController {

  private final MultipartUploadService multipartUploadService;
  private final UserContext userContext;

  public FileMultipartUploadController(
      MultipartUploadService multipartUploadService, UserContext userContext) {
    this.multipartUploadService = multipartUploadService;
    this.userContext = userContext;
  }

  @PostMapping
  public InitiateMultipartUploadResponse initiate(
      @Valid @RequestBody InitiateMultipartUploadRequest request) {
    return InitiateMultipartUploadResponse.from(
        multipartUploadService.initiateMultipart(
            new com.company.platform.file.application.InitiateMultipartUploadCommand(
                request.categoryCode(),
                request.originalFilename(),
                request.mimeType(),
                request.ownerType(),
                userContext.userId(),
                userContext.userId())));
  }

  @PostMapping("/{fileAssetId}/part-urls")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public PartUrlsResponse partUrls(
      @PathVariable UUID fileAssetId, @Valid @RequestBody PartUrlsRequest request) {
    return PartUrlsResponse.from(
        multipartUploadService.generatePartUrls(fileAssetId, request.partNumbers()));
  }

  @GetMapping("/{fileAssetId}/parts")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public UploadedPartsResponse parts(@PathVariable UUID fileAssetId) {
    return UploadedPartsResponse.from(multipartUploadService.listUploadedParts(fileAssetId));
  }

  @PostMapping("/{fileAssetId}/complete")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public FileAssetResponse complete(
      @PathVariable UUID fileAssetId, @Valid @RequestBody CompleteMultipartUploadRequest request) {
    FileAsset asset = multipartUploadService.completeMultipart(request.toCommand(fileAssetId));
    return FileAssetResponse.from(asset);
  }

  @PostMapping("/{fileAssetId}/abort")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public void abort(@PathVariable UUID fileAssetId) {
    multipartUploadService.abortMultipart(fileAssetId);
  }
}
```

- [ ] **Step 5: Map new exceptions in `FileApiExceptionHandler`**

```java
  @ExceptionHandler(MultipartNotEnabledException.class)
  public ResponseEntity<ApiError> handleMultipartNotEnabled(MultipartNotEnabledException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(InvalidPartNumberException.class)
  public ResponseEntity<ApiError> handleInvalidPartNumber(InvalidPartNumberException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }
```

Add imports for the two exception classes.

- [ ] **Step 6: Run to verify pass**

Run: `mvn spotless:apply && mvn test -Dtest=FileMultipartUploadControllerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/platform/file/api src/test/java/com/company/platform/file/api/FileMultipartUploadControllerTest.java
git commit -m "$(cat <<'EOF'
feat(file): /api/files/multipart REST controller

initiate / part-urls / parts / complete / abort; @RequiresAccess(FILE_ASSET,WRITE)
on the resource-scoped endpoints.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Task 8: End-to-end integration test (HTTP → MinIO)

**Files:**
- Test: `src/test/java/com/company/platform/file/api/MultipartUploadEndToEndIT.java` (`@SpringBootTest(webEnvironment = MOCK)` + `@AutoConfigureMockMvc` + Postgres + MinIO containers, mirroring `FileUploadControllerIT`).

**Interfaces:**
- Consumes: the full HTTP API from Task 7; a raw `java.net.http.HttpClient` for the direct-to-MinIO part PUTs.

- [ ] **Step 1: Write the E2E test**

Cover, each as its own `@Test`:

1. `should_completeUpload_when_happyPath` — seed a multipart-enabled category (`multipartPartSizeBytes = 5 MiB`, `maxSizeBytes = 50 MiB`); `POST /api/files/multipart` as owner; `POST /part-urls` for `[1,2]`; `HttpClient` PUT a 5 MiB part 1 + small part 2, capture `ETag` headers; `POST /complete` with the etags + a checksum; assert 200 and `storageStatus == "CLEAN"`; assert `multipart_upload_id` is null in DB.
2. `should_resume_when_reRequestingUrlsAndListingParts` — initiate, upload only part 1, `GET /parts` → assert it lists part 1; `POST /part-urls` for `[2]` again; upload part 2; complete → CLEAN.
3. `should_return403_when_strangerRequestsPartUrls` — initiate as owner, `POST /part-urls` with a different `X-User-Id` → 403.
4. `should_return204AndAbort_when_ownerAborts` — initiate, `POST /abort` → 204; `GET /parts` → 409 (upload no longer open) and DB row soft-deleted.
5. `should_rejectComplete_when_objectExceedsCategoryMax` — category `maxSizeBytes = 1_000`; upload one 5 MiB part; `POST /complete` → 422; asset not CLEAN.
6. `should_return409_when_initiatingOnNonMultipartCategory` — use a category with null `multipartPartSizeBytes` → 409.

Use the `X-User-Id` / `X-User-Roles` header pattern from `FileUploadControllerIT`.

- [ ] **Step 2: Run**

Run: `mvn test -Dtest=MultipartUploadEndToEndIT`
Expected: initially some assertions may fail — fix production code (not the test) until green. Do not weaken assertions.

- [ ] **Step 3: Full regression**

Run: `mvn verify -Pintegration-test`
Expected: all ITs pass (Docker must be running).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/company/platform/file/api/MultipartUploadEndToEndIT.java
git commit -m "$(cat <<'EOF'
test(file): end-to-end multipart upload IT

Happy path, resume, cross-user 403, abort, oversize reject, non-multipart category.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Task 9: Cleanup sweep for stale multipart uploads + docs

**Files:**
- Modify: `src/main/java/com/company/platform/file/domain/FileAssetRepository.java` — `findStaleMultipartUploads`.
- Modify: `src/main/java/com/company/platform/shared/cleanup/CleanupService.java`
- Modify: `src/main/java/com/company/platform/shared/cleanup/CleanupProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/company/platform/shared/cleanup/CleanupServiceTest.java`, `CleanupSchedulerTest.java` (constructor arg)
- Modify: `CLAUDE.md` (directory-structure note)
- Test: extend `src/test/java/com/company/platform/shared/cleanup/CleanupServiceIT.java`

**Interfaces:**
- Consumes: `MultipartStorageService` (new constructor arg on `CleanupService`), `FileAssetRepository`.
- Produces:
  - `FileAssetRepository#findStaleMultipartUploads(Instant createdBefore, int limit) : List<FileAsset>` — `storage_status = 'UPLOADING' AND multipart_upload_id IS NOT NULL AND created_at < :createdBefore AND deleted_at IS NULL`, `ORDER BY created_at LIMIT :limit` (native query for `LIMIT`).
  - `CleanupProperties(Duration interval, int batchSize, Duration jobDoneRetention, Duration multipartUploadTimeout)`
  - `CleanupService#runCleanup()` also calls `abortStaleMultipartUploads()`.

- [x] **Step 1: Write the failing test (extend `CleanupServiceIT`)**

```java
  @Test
  void should_abortAndSoftDelete_when_multipartUploadStale() {
    // seed a multipart-enabled category + a real initiate via MultipartUploadService,
    // then back-date created_at with a native UPDATE so it is older than multipartUploadTimeout.
    // run cleanupService.runCleanup();
    // assert: asset.deletedAt != null, asset.multipartUploadId == null,
    //         and multipartStorageService.listParts(...) is empty / throws no-such-upload.
  }
```

Wire the IT with `@Autowired MultipartUploadService`, `MultipartStorageService`, and set `app.cleanup.multipart-upload-timeout=1s` via `@DynamicPropertySource` or `@TestPropertySource` so the back-dating window is tiny.

- [x] **Step 2: Run to verify failure**

Run: `mvn test -Dtest=CleanupServiceIT`
Expected: FAIL — method + config missing.

- [x] **Step 3: Add repo query**

```java
  @Query(
      value =
          "SELECT * FROM file_assets WHERE storage_status = 'UPLOADING' "
              + "AND multipart_upload_id IS NOT NULL AND deleted_at IS NULL "
              + "AND created_at < :createdBefore ORDER BY created_at LIMIT :limit",
      nativeQuery = true)
  java.util.List<FileAsset> findStaleMultipartUploads(
      @Param("createdBefore") Instant createdBefore, @Param("limit") int limit);
```

- [x] **Step 4: Extend `CleanupProperties` + `application.yml`**

```java
public record CleanupProperties(
    Duration interval, int batchSize, Duration jobDoneRetention, Duration multipartUploadTimeout) {}
```

`application.yml` under `app.cleanup`:

```yaml
    multipart-upload-timeout: ${APP_CLEANUP_MULTIPART_UPLOAD_TIMEOUT:2h}
```

Update `CleanupServiceTest` / `CleanupSchedulerTest` `new CleanupProperties(...)` to the 4-arg form (`, Duration.ofHours(2)`).

- [x] **Step 5: Extend `CleanupService`**

Add `MultipartStorageService multipartStorageService` as a constructor param (update `CleanupServiceTest`'s `new CleanupService(...)` accordingly, passing a Mockito mock). Then:

```java
  @Transactional
  public void runCleanup() {
    if (!advisoryLockService.tryLock(CLEANUP_LOCK_KEY)) {
      return;
    }
    cleanupExpiredFileAssets();
    expireDoneJobs();
    abortStaleMultipartUploads();
  }

  private void abortStaleMultipartUploads() {
    Instant threshold = Instant.now().minus(properties.multipartUploadTimeout());
    for (FileAsset asset :
        fileAssetRepository.findStaleMultipartUploads(threshold, properties.batchSize())) {
      try {
        multipartStorageService.abortMultipartUpload(
            asset.getBucketName(), asset.getObjectKey(), asset.getMultipartUploadId());
      } catch (RuntimeException e) {
        // upload đã biến mất phía storage — vẫn dọn metadata bên dưới
      }
      Instant now = Instant.now();
      fileAssetRepository.softDelete(asset.getId(), now);
      fileAssetRepository.clearMultipartUploadId(asset.getId(), now);
    }
  }
```

Add imports: `FileAsset`.

- [x] **Step 6: Update `CLAUDE.md`**

In the "Cấu trúc thư mục" `file/` block, add under `storage/`:

```
│   ├── storage/              # MinioStorageService, PresignedUrlService
│   │   └── multipart/         # AWS SDK v2 S3 client + MultipartStorageService (luồng multipart)
```

- [x] **Step 7: Run to verify pass**

Run: `mvn spotless:apply && mvn test -Dtest='CleanupServiceIT,CleanupServiceTest,CleanupSchedulerTest'`
Expected: PASS.

- [x] **Step 8: Full build**

Run: `mvn clean install` then `mvn verify -Pintegration-test`
Expected: BUILD SUCCESS, all tests green, Spotless clean.

- [x] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(cleanup): abort stale multipart uploads

CleanupService sweeps file_assets stuck in UPLOADING with an open
multipart_upload_id past app.cleanup.multipart-upload-timeout.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0137GsARxPty7MmAGHTZP64Y
EOF
)"
```

---

## Self-Review

**Spec coverage (design doc 8.1):**
- State machine unchanged, reuse CAS-to-CLEAN → Task 4 (`FileAssetStatusFinalizer`), Task 6 (`completeMultipart` delegates).
- Client does not send size; backend decides part size from config → Task 5 (`initiateMultipart` reads `multipartPartSizeBytes`, no size in command/request).
- `max_size_bytes` enforced at complete via real `statObject` size → Task 6.
- ETag not trusted for integrity; client SHA-256 + size → Task 6 (`finalizeToClean` stores client checksum; size from `statObject`).
- Config `multipart_part_size_bytes` (null = disabled), column `multipart_upload_id` → Task 1.
- 5 endpoints (initiate / part-urls / parts / complete / abort) → Task 7.
- `maxParts = ceil(max/partSize)`, reject `partNumber > min(maxParts, 10000)` → Task 5 (`maxParts`, `InvalidPartNumberException`).
- Resume via `GET /parts` (ListParts) + re-request `part-urls` → Task 6 (`listUploadedParts`), Task 8 (resume test).
- Auth `@RequiresAccess(FILE_ASSET, WRITE)` on complete/abort/part-urls/parts; initiate open → Task 7.
- Cleanup sweep primary; lifecycle rule deferred → Task 9.
- AWS SDK isolated to `file.storage.multipart`; built from `app.minio.*`; path-style + fixed region + checksum-off → Task 2, plus Global Constraint on import location.

**Placeholder scan:** Task 4's unit test carries an explicit "delete it and rely on the IT" fallback (the refactor is behaviour-preserving and IT-covered) — that is a deliberate instruction, not a TODO. Task 8 lists 6 concrete named test cases. Task 9 Step 1 gives the assertion list rather than full code because it composes already-shown helpers — acceptable, the shape is fully specified.

**Type consistency:** `MultipartStorageService.UploadedPart(int, String, long)` and `.CompletedPartRef(int, String)` are nested records referenced consistently in Tasks 3/5/6/7. `CompleteMultipartUploadCommand.PartRef(int, String)` (application) vs `CompletedPartRef` (storage) are distinct on purpose — the service maps between them (Task 6 Step 4). `MultipartUploadInitiation(UUID, long, int)` consistent across Tasks 5/7. `PartUrl(int, String, Instant)` consistent Tasks 5/7.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-03-multipart-upload.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
