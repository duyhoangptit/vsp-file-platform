package com.company.platform.file.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.file.application.CompleteMultipartUploadCommand;
import com.company.platform.file.application.InitiateMultipartUploadCommand;
import com.company.platform.file.application.MultipartUploadInitiation;
import com.company.platform.file.application.MultipartUploadService;
import com.company.platform.file.application.PartUrl;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.storage.multipart.MultipartStorageService.UploadedPart;
import com.company.platform.shared.auth.UserContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileMultipartUploadController.class)
class FileMultipartUploadControllerTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID FILE_ASSET_ID = UUID.randomUUID();

  @Autowired private MockMvc mockMvc;

  @MockitoBean private MultipartUploadService multipartUploadService;

  @MockitoBean private UserContext userContext;

  @BeforeEach
  void setUp() {
    when(userContext.userId()).thenReturn(USER_ID);
  }

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
        .andExpect(jsonPath("$.fileAssetId").value(FILE_ASSET_ID.toString()))
        .andExpect(jsonPath("$.partSizeBytes").value(67108864))
        .andExpect(jsonPath("$.maxParts").value(8));
  }

  @Test
  void should_buildCommandWithUserIdFromContext_when_initiate() throws Exception {
    when(multipartUploadService.initiateMultipart(any()))
        .thenReturn(new MultipartUploadInitiation(FILE_ASSET_ID, 1L, 1));

    mockMvc
        .perform(
            post("/api/files/multipart")
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"categoryCode\":\"IMPORT_RAW\",\"originalFilename\":\"big.csv\","
                        + "\"mimeType\":\"text/csv\",\"ownerType\":\"USER\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<InitiateMultipartUploadCommand> captor =
        ArgumentCaptor.forClass(InitiateMultipartUploadCommand.class);
    verify(multipartUploadService).initiateMultipart(captor.capture());
    assertThat(captor.getValue().ownerId()).isEqualTo(USER_ID);
    assertThat(captor.getValue().uploadedBy()).isEqualTo(USER_ID);
    assertThat(captor.getValue().categoryCode()).isEqualTo("IMPORT_RAW");
  }

  @Test
  void should_return400_when_initiateBodyMissingRequiredFields() throws Exception {
    mockMvc
        .perform(
            post("/api/files/multipart")
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryCode\":\"IMPORT_RAW\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return200WithUrls_when_requestingPartUrls() throws Exception {
    when(multipartUploadService.generatePartUrls(eq(FILE_ASSET_ID), any()))
        .thenReturn(
            List.of(new PartUrl(1, "http://minio/part1", Instant.parse("2026-09-03T10:00:00Z"))));

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
  void should_return400_when_partUrlsRequestHasEmptyList() throws Exception {
    mockMvc
        .perform(
            post("/api/files/multipart/" + FILE_ASSET_ID + "/part-urls")
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"partNumbers\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return200WithParts_when_listingUploadedParts() throws Exception {
    when(multipartUploadService.listUploadedParts(FILE_ASSET_ID))
        .thenReturn(List.of(new UploadedPart(1, "etag-1", 1024L)));

    mockMvc
        .perform(
            get("/api/files/multipart/" + FILE_ASSET_ID + "/parts")
                .header("X-User-Id", USER_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[0].partNumber").value(1))
        .andExpect(jsonPath("$.parts[0].etag").value("etag-1"))
        .andExpect(jsonPath("$.parts[0].sizeBytes").value(1024));
  }

  @Test
  void should_return200WithFileAsset_when_complete() throws Exception {
    when(multipartUploadService.completeMultipart(any()))
        .thenReturn(
            new FileAsset(
                FILE_ASSET_ID,
                "IMPORT_RAW",
                "import-raw",
                "object-key",
                "big.csv",
                "text/csv",
                2048,
                "USER",
                USER_ID,
                USER_ID,
                null));

    mockMvc
        .perform(
            post("/api/files/multipart/" + FILE_ASSET_ID + "/complete")
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"parts\":[{\"partNumber\":1,\"etag\":\"etag-1\"}],"
                        + "\"checksumSha256\":\"deadbeef\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(FILE_ASSET_ID.toString()))
        .andExpect(jsonPath("$.originalFilename").value("big.csv"));

    ArgumentCaptor<CompleteMultipartUploadCommand> captor =
        ArgumentCaptor.forClass(CompleteMultipartUploadCommand.class);
    verify(multipartUploadService).completeMultipart(captor.capture());
    assertThat(captor.getValue().fileAssetId()).isEqualTo(FILE_ASSET_ID);
    assertThat(captor.getValue().checksumSha256()).isEqualTo("deadbeef");
    assertThat(captor.getValue().parts()).hasSize(1);
    assertThat(captor.getValue().parts().get(0).partNumber()).isEqualTo(1);
    assertThat(captor.getValue().parts().get(0).etag()).isEqualTo("etag-1");
  }

  @Test
  void should_return400_when_completeBodyHasBlankEtag() throws Exception {
    mockMvc
        .perform(
            post("/api/files/multipart/" + FILE_ASSET_ID + "/complete")
                .header("X-User-Id", USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"parts\":[{\"partNumber\":1,\"etag\":\"\"}],"
                        + "\"checksumSha256\":\"deadbeef\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return204_when_abort() throws Exception {
    mockMvc
        .perform(
            post("/api/files/multipart/" + FILE_ASSET_ID + "/abort")
                .header("X-User-Id", USER_ID.toString()))
        .andExpect(status().isNoContent());

    verify(multipartUploadService).abortMultipart(FILE_ASSET_ID);
  }
}
