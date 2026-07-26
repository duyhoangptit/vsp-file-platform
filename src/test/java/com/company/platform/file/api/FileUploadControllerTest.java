package com.company.platform.file.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.application.FileUploadInitiation;
import com.company.platform.file.application.FileUploadService;
import com.company.platform.file.application.InitiateFileUploadCommand;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.ResourceAuthorizationAspect;
import com.company.platform.shared.auth.ResourceAuthorizationService;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContextConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileUploadController.class)
@Import({
  UserContextConfig.class,
  ResourceAuthorizationAspect.class,
  org.springframework.boot.autoconfigure.aop.AopAutoConfiguration.class
})
class FileUploadControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FileUploadService fileUploadService;

  @MockitoBean private ResourceAuthorizationService resourceAuthorizationService;

  private static final String REQUEST_BODY =
      """
      {
        "categoryCode": "AVATAR",
        "originalFilename": "avatar.png",
        "mimeType": "image/png",
        "sizeBytes": 1024,
        "ownerType": "USER"
      }
      """;

  @Test
  void should_return401_when_userIdHeaderIsMissing() throws Exception {
    mockMvc
        .perform(post("/api/files").contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_useUserIdFromHeader_when_initiatingUpload() throws Exception {
    UUID userId = UUID.randomUUID();
    when(fileUploadService.initiateUpload(any()))
        .thenReturn(
            new FileUploadInitiation(UUID.randomUUID(), "http://minio/put-url", Instant.now()));

    mockMvc
        .perform(
            post("/api/files")
                .header("X-User-Id", userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
        .andExpect(status().isOk());

    ArgumentCaptor<InitiateFileUploadCommand> captor =
        ArgumentCaptor.forClass(InitiateFileUploadCommand.class);
    verify(fileUploadService).initiateUpload(captor.capture());
    assertThat(captor.getValue().ownerId()).isEqualTo(userId);
    assertThat(captor.getValue().uploadedBy()).isEqualTo(userId);
  }

  @Test
  void should_return200_when_confirmUpload_and_resourceAuthorizationAllows() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    when(resourceAuthorizationService.canAccess(
            any(), eq(ResourceType.FILE_ASSET), any(), eq(Action.WRITE)))
        .thenReturn(true);
    when(fileUploadService.confirmUpload(eq(fileAssetId), any()))
        .thenReturn(
            new FileAsset(
                fileAssetId,
                "AVATAR",
                "avatar-bucket",
                "object-key",
                "avatar.png",
                "image/png",
                1024,
                "USER",
                userId,
                userId,
                null));

    mockMvc
        .perform(
            post("/api/files/" + fileAssetId + "/confirm")
                .header("X-User-Id", userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checksumSha256\": \"deadbeef\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void should_return403_when_confirmUpload_and_resourceAuthorizationDenies() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    when(resourceAuthorizationService.canAccess(
            any(), eq(ResourceType.FILE_ASSET), any(), eq(Action.WRITE)))
        .thenReturn(false);

    mockMvc
        .perform(
            post("/api/files/" + fileAssetId + "/confirm")
                .header("X-User-Id", userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checksumSha256\": \"deadbeef\"}"))
        .andExpect(status().isForbidden());

    verify(fileUploadService, org.mockito.Mockito.never()).confirmUpload(any(), any());
  }

  @Test
  void should_passFileAssetIdAsResourceId_toAuthorizationCheck() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID fileAssetId = UUID.randomUUID();
    when(resourceAuthorizationService.canAccess(any(), any(), any(), any())).thenReturn(false);

    mockMvc.perform(
        post("/api/files/" + fileAssetId + "/confirm")
            .header("X-User-Id", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksumSha256\": \"deadbeef\"}"));

    verify(resourceAuthorizationService)
        .canAccess(
            any(), eq(ResourceType.FILE_ASSET), eq(fileAssetId.toString()), eq(Action.WRITE));
  }
}
