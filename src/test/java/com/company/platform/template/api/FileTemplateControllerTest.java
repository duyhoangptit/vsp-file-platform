package com.company.platform.template.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContextConfig;
import com.company.platform.template.application.FileTemplateService;
import com.company.platform.template.application.TemplateUploadInitiation;
import com.company.platform.template.domain.FileTemplateNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileTemplateController.class)
@Import(UserContextConfig.class)
class FileTemplateControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FileTemplateService fileTemplateService;

  private static final String INITIATE_BODY =
      """
      {
        "category": "MEMBER",
        "templateCode": "MEMBER_IMPORT",
        "originalFilename": "member-import-template.xlsx",
        "mimeType": "text/csv",
        "sizeBytes": 20480
      }
      """;

  @Test
  void should_return401_when_userIdHeaderIsMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(INITIATE_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_return403_when_serviceThrowsAccessDenied_onInitiate() throws Exception {
    when(fileTemplateService.initiateUpload(any(), any()))
        .thenThrow(new AccessDeniedException(ResourceType.FILE_TEMPLATE, "MEMBER:MEMBER_IMPORT"));

    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(INITIATE_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_return200_when_initiateUploadSucceeds() throws Exception {
    when(fileTemplateService.initiateUpload(any(), any()))
        .thenReturn(
            new TemplateUploadInitiation(UUID.randomUUID(), "http://minio/put-url", Instant.now()));

    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(INITIATE_BODY))
        .andExpect(status().isOk());
  }

  @Test
  void should_return404_when_downloadTemplateNotFound() throws Exception {
    when(fileTemplateService.generateDownloadUrl(any(), any(), any(), any()))
        .thenThrow(new FileTemplateNotFoundException("MEMBER", "MEMBER_IMPORT"));

    mockMvc
        .perform(
            get("/api/templates/download-url")
                .header("X-User-Id", UUID.randomUUID().toString())
                .param("category", "MEMBER")
                .param("templateCode", "MEMBER_IMPORT"))
        .andExpect(status().isNotFound());
  }

  @Test
  void should_return400_when_categoryMissing_onInitiate() throws Exception {
    mockMvc
        .perform(
            post("/api/templates/upload/initiate")
                .header("X-User-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateCode": "MEMBER_IMPORT",
                      "originalFilename": "x.xlsx",
                      "mimeType": "text/csv",
                      "sizeBytes": 100
                    }
                    """))
        .andExpect(status().isBadRequest());
  }
}
