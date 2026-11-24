package com.company.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Hợp đồng OpenAPI của admin API (frontend sinh client từ đây). Chạy với {@code
 * -Dopenapi.snapshot=docs/openapi/file-admin.json} để ghi lại snapshot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminOpenApiIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MockMvc mockMvc;

  @Test
  void should_exposeAllAdminEndpoints_when_readingApiDocsWithoutToken() throws Exception {
    String json =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/v3/api-docs"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(json)
        .contains(
            "\"/api/admin/files\"",
            "\"/api/admin/files/{id}\"",
            "\"/api/admin/files/{id}/history\"",
            "\"/api/admin/files/stats\"",
            "\"/api/admin/file-downloads\"",
            "\"/api/admin/config-changes\"",
            "\"/api/admin/virus-scan\"",
            "\"/api/admin/file-categories\"",
            "\"/api/admin/file-categories/{code}\"",
            "\"bearer-jwt\"");

    String snapshot = System.getProperty("openapi.snapshot");
    if (snapshot != null && !snapshot.isBlank()) {
      Path out = Path.of(snapshot);
      Files.createDirectories(out.getParent());
      Files.writeString(out, json);
    }
  }
}
