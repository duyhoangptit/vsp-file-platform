package com.company.platform.file.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.application.FileBytesResult;
import com.company.platform.file.application.FileDownloadService;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.shared.auth.InternalApiKeyInterceptor;
import com.company.platform.shared.config.InternalApiWebConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileInternalDownloadController.class)
@Import({InternalApiKeyInterceptor.class, InternalApiWebConfig.class})
@TestPropertySource(properties = "app.internal-api.key=test-internal-api-key")
class FileInternalDownloadControllerTest {

  private static final String HEADER = "X-Internal-Api-Key";
  private static final String CORRECT_KEY = "test-internal-api-key";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FileDownloadService fileDownloadService;

  @Test
  void should_return401_when_internalApiKeyHeaderMissing() throws Exception {
    UUID fileAssetId = UUID.randomUUID();

    mockMvc
        .perform(get("/api/internal/files/" + fileAssetId + "/bytes"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_return401_when_internalApiKeyHeaderWrong() throws Exception {
    UUID fileAssetId = UUID.randomUUID();

    mockMvc
        .perform(get("/api/internal/files/" + fileAssetId + "/bytes").header(HEADER, "wrong-key"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_return404_when_fileAssetNotFound() throws Exception {
    UUID fileAssetId = UUID.randomUUID();
    when(fileDownloadService.prepareBytesDownload(fileAssetId))
        .thenThrow(new FileAssetNotFoundException(fileAssetId));

    mockMvc
        .perform(get("/api/internal/files/" + fileAssetId + "/bytes").header(HEADER, CORRECT_KEY))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_return409_when_storageStatusNotClean() throws Exception {
    UUID fileAssetId = UUID.randomUUID();
    when(fileDownloadService.prepareBytesDownload(fileAssetId))
        .thenThrow(
            new FileNotAvailableForDownloadException(fileAssetId, StorageStatus.UPLOADED_DMZ));

    mockMvc
        .perform(get("/api/internal/files/" + fileAssetId + "/bytes").header(HEADER, CORRECT_KEY))
        .andExpect(status().isConflict());
  }

  @Test
  void should_streamBytesWithHeaders_when_fileAvailable() throws Exception {
    UUID fileAssetId = UUID.randomUUID();
    byte[] fileContent = "hello bytes".getBytes(StandardCharsets.UTF_8);
    when(fileDownloadService.prepareBytesDownload(fileAssetId))
        .thenReturn(
            new FileBytesResult(
                new ByteArrayInputStream(fileContent), "report.csv", "text/csv", fileContent.length));

    mockMvc
        .perform(get("/api/internal/files/" + fileAssetId + "/bytes").header(HEADER, CORRECT_KEY))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "text/csv"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    allOf(
                        containsString("attachment"), containsString("filename=\"report.csv\""))))
        .andExpect(content().bytes(fileContent));
  }

  @Test
  void should_notCallService_when_internalApiKeyHeaderMissing() throws Exception {
    UUID fileAssetId = UUID.randomUUID();

    mockMvc.perform(get("/api/internal/files/" + fileAssetId + "/bytes"));

    verify(fileDownloadService, never()).prepareBytesDownload(any());
  }
}
