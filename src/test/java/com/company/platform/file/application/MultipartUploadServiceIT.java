package com.company.platform.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FileVerificationFailedException;
import com.company.platform.file.domain.InvalidPartNumberException;
import com.company.platform.file.domain.MultipartNotEnabledException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.multipart.MultipartStorageService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  @Autowired MultipartStorageService storage;

  private final HttpClient http = HttpClient.newHttpClient();

  private String mpCategory;
  private String plainCategory;
  private String oversizeCategory;

  private String putPart(String url, byte[] body) throws Exception {
    var resp =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(resp.statusCode()).isEqualTo(200);
    return resp.headers().firstValue("ETag").orElseThrow();
  }

  @BeforeEach
  void seed() {
    String s = UUID.randomUUID().toString().substring(0, 8);
    mpCategory = "MP_" + s;
    plainCategory = "PLAIN_" + s;
    oversizeCategory = "OVER_" + s;
    categoryRepository.save(
        new FileCategory(
            mpCategory,
            "mp-" + s,
            List.of("text/csv"),
            500L * 1024 * 1024,
            false,
            false,
            null,
            64L * 1024 * 1024));
    categoryRepository.save(
        new FileCategory(
            plainCategory,
            "plain-" + s,
            List.of("text/csv"),
            1_000_000L,
            false,
            false,
            null,
            null));
    categoryRepository.save(
        new FileCategory(
            oversizeCategory,
            "over-" + s,
            List.of("text/csv"),
            1_000L,
            false,
            false,
            null,
            6L * 1024 * 1024));
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
    assertThat(assetRepository.findById(init.fileAssetId()).orElseThrow().getMultipartUploadId())
        .isNull();
  }

  @Test
  void should_listUploadedParts_when_someUploaded() throws Exception {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));
    byte[] p1 = new byte[6 * 1024 * 1024];
    String etag =
        putPart(service.generatePartUrls(init.fileAssetId(), List.of(1)).get(0).url(), p1);

    List<MultipartStorageService.UploadedPart> parts =
        service.listUploadedParts(init.fileAssetId());

    assertThat(parts)
        .extracting(MultipartStorageService.UploadedPart::partNumber)
        .containsExactly(1);
    assertThat(parts.get(0).sizeBytes()).isEqualTo(p1.length);
    assertThat(parts.get(0).etag()).isEqualTo(etag);
  }

  @Test
  void should_rejectListUploadedParts_when_noOpenUpload() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));
    service.abortMultipart(init.fileAssetId());

    assertThatThrownBy(() -> service.listUploadedParts(init.fileAssetId()))
        .isInstanceOf(com.company.platform.file.domain.InvalidFileAssetStateException.class);
  }

  @Test
  void should_softDeleteAsset_when_abort() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    service.abortMultipart(init.fileAssetId());

    FileAsset asset = assetRepository.findById(init.fileAssetId()).orElseThrow();
    assertThat(asset.getDeletedAt()).isNotNull();
    assertThat(asset.getMultipartUploadId()).isNull();
  }

  @Test
  void should_beIdempotent_when_abortCalledTwice() {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(mpCategory));

    service.abortMultipart(init.fileAssetId());
    service.abortMultipart(init.fileAssetId());

    assertThat(assetRepository.findById(init.fileAssetId()).orElseThrow().getMultipartUploadId())
        .isNull();
  }

  @Test
  void should_rejectAndNotReachClean_when_completedObjectExceedsCategoryMax() throws Exception {
    MultipartUploadInitiation init = service.initiateMultipart(cmd(oversizeCategory));
    byte[] p1 = new byte[6 * 1024 * 1024];
    String e1 = putPart(service.generatePartUrls(init.fileAssetId(), List.of(1)).get(0).url(), p1);

    assertThatThrownBy(
            () ->
                service.completeMultipart(
                    new CompleteMultipartUploadCommand(
                        init.fileAssetId(),
                        List.of(new CompleteMultipartUploadCommand.PartRef(1, e1)),
                        "cafe")))
        .isInstanceOf(FileVerificationFailedException.class);

    assertThat(assetRepository.findById(init.fileAssetId()).orElseThrow().getStorageStatus())
        .isNotEqualTo(StorageStatus.CLEAN);
  }
}
