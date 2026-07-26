package com.company.platform.export.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.platform.job.application.JobStatusService;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.job.domain.JobType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Giả lập ExportUserCsvProcessor gọi context.updateProgress() sau mỗi batch trong lúc doExecute()
 * chạy (mục 11 design doc) — gọi thẳng JobStatusService.updateProgress(), đúng cơ chế mà
 * AbstractJobProcessor bọc vào JobContext cho processor thật, không mock GET /exports/{id} hay DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ExportProgressReportingIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private MockMvc mockMvc;
  @Autowired private JobRepository jobRepository;
  @Autowired private JobStatusService jobStatusService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void cleanJobs() {
    jobRepository.deleteAll();
  }

  @Test
  void should_reflectIncreasingProcessedUnits_when_processorReportsProgressAcrossBatches()
      throws Exception {
    UUID ownerId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    jobRepository.save(
        new Job(jobId, JobType.EXPORT_USER_CSV, Map.of(), null, null, null, ownerId));
    jobStatusService.tryMarkProcessing(jobId);

    // page-size giả định 50, tổng 150 dòng -> 3 batch, cộng dồn đúng theo cách
    // ExportUserCsvProcessor
    // gọi context.updateProgress(processedUnits) sau mỗi trang (giá trị cộng dồn, không phải
    // delta từng trang).
    long[] cumulativeBatches = {50, 100, 150};
    long previousProcessedUnits = 0;
    for (long processedUnits : cumulativeBatches) {
      jobStatusService.updateProgress(jobId, processedUnits);

      String body =
          mockMvc
              .perform(get("/exports/" + jobId).header("X-User-Id", ownerId.toString()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      JsonNode json = objectMapper.readTree(body);

      assertThat(json.get("processedUnits").asLong())
          .as("GET /exports/%s phải phản ánh đúng batch vừa report", jobId)
          .isEqualTo(processedUnits)
          .isGreaterThan(previousProcessedUnits);
      previousProcessedUnits = processedUnits;
    }
  }
}
