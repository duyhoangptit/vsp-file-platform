package com.company.platform.job.processor;

import com.company.platform.job.domain.JobType;
import java.util.Map;
import java.util.UUID;

public record JobContext(
    UUID jobId, JobType jobType, Map<String, Object> payload, ProgressReporter progressReporter) {

  // Constructor tiện dùng cho nơi tạo JobContext gốc (JobScheduler) — chưa biết gì về progress
  // reporting. AbstractJobProcessor tự bọc lại context với 1 ProgressReporter thật (ghi
  // jobs.processed_units) trước khi truyền cho validate()/doExecute(), nên business processor
  // gọi context.updateProgress(count) là ghi thật xuống DB; còn nơi nào dựng JobContext trực
  // tiếp (test gọi thẳng doExecute()) thì updateProgress() chỉ no-op.
  public JobContext(UUID jobId, JobType jobType, Map<String, Object> payload) {
    this(jobId, jobType, payload, processedUnits -> {});
  }

  /** Business processor gọi định kỳ theo batch trong lúc doExecute() chạy (mục 11 design doc). */
  public void updateProgress(long processedUnits) {
    progressReporter.report(processedUnits);
  }

  @FunctionalInterface
  public interface ProgressReporter {
    // Bất kỳ ai muốn báo cáo tiến độ, hãy đưa cho tôi số lượng công việc đã làm xong
    // (processedUnits), tôi sẽ xử lý nó
    // long processedUnits: Tham số đầu vào đại diện cho tiến trình hiện tại (ví dụ: số bytes đã tải
    // xong, số dòng code đã đọc, số lượng tệp đã xử lý).
    void report(long processedUnits);
  }
}
