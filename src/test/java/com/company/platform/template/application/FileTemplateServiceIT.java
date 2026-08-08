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
