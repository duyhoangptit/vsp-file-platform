package com.company.platform.export.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.export.application.ExportJobService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobNotFoundException;
import com.company.platform.job.domain.JobType;
import com.company.platform.shared.auth.AccessDeniedException;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.ResourceAuthorizationAspect;
import com.company.platform.shared.auth.ResourceAuthorizationService;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContextConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExportController.class)
@Import({
  UserContextConfig.class,
  ResourceAuthorizationAspect.class,
  org.springframework.boot.autoconfigure.aop.AopAutoConfiguration.class
})
class ExportControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ExportJobService exportJobService;

  @MockitoBean private ResourceAuthorizationService resourceAuthorizationService;

  @Test
  void should_return401_when_userIdHeaderIsMissing() throws Exception {
    mockMvc.perform(post("/exports/users")).andExpect(status().isUnauthorized());
  }

  @Test
  void should_createExportJob_when_userIsAdmin() throws Exception {
    UUID userId = UUID.randomUUID();
    when(exportJobService.submitUserCsvExport(any(), eq("idem-1")))
        .thenReturn(
            new Job(
                UUID.randomUUID(), JobType.EXPORT_USER_CSV, Map.of(), null, null, null, userId));

    mockMvc
        .perform(
            post("/exports/users")
                .header("X-User-Id", userId.toString())
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\": \"idem-1\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void should_return403_when_userIsNotAdmin() throws Exception {
    UUID userId = UUID.randomUUID();
    when(exportJobService.submitUserCsvExport(any(), any()))
        .thenThrow(new AccessDeniedException(ResourceType.JOB, "EXPORT_USER_CSV"));

    mockMvc
        .perform(
            post("/exports/users")
                .header("X-User-Id", userId.toString())
                .header("X-User-Roles", "MEMBER")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_return200_when_gettingExportJob_and_resourceAuthorizationAllows() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(resourceAuthorizationService.canAccess(
            any(), eq(ResourceType.JOB), any(), eq(Action.READ)))
        .thenReturn(true);
    when(exportJobService.getJob(jobId))
        .thenReturn(new Job(jobId, JobType.EXPORT_USER_CSV, Map.of(), null, null, null, userId));

    mockMvc
        .perform(get("/exports/" + jobId).header("X-User-Id", userId.toString()))
        .andExpect(status().isOk());
  }

  @Test
  void should_return403_when_gettingExportJob_and_resourceAuthorizationDenies() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(resourceAuthorizationService.canAccess(
            any(), eq(ResourceType.JOB), any(), eq(Action.READ)))
        .thenReturn(false);

    mockMvc
        .perform(get("/exports/" + jobId).header("X-User-Id", userId.toString()))
        .andExpect(status().isForbidden());

    verify(exportJobService, never()).getJob(any());
  }

  @Test
  void should_return404_when_gettingExportJob_and_jobDoesNotExist() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(resourceAuthorizationService.canAccess(
            any(), eq(ResourceType.JOB), any(), eq(Action.READ)))
        .thenReturn(true);
    when(exportJobService.getJob(jobId)).thenThrow(new JobNotFoundException(jobId));

    mockMvc
        .perform(get("/exports/" + jobId).header("X-User-Id", userId.toString()))
        .andExpect(status().isNotFound());
  }
}
