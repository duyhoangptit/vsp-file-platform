package com.company.platform.import_.api;

import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(
    description =
        "Trạng thái job import user, dùng để FE poll và dựng progress bar (mục 11 design doc).")
public record ImportJobResponse(
    @Schema(description = "ID job, dùng để gọi GET /imports/{id} poll trạng thái.") UUID id,
    @Schema(
            description =
                "PENDING: đang chờ xử lý. PROCESSING: đang chạy (poll processedUnits/totalUnits để"
                    + " hiển thị %). DONE: xong. FAILED: lỗi, xem errorMessage. EXPIRED: đã dọn dẹp"
                    + " do hết hạn retention.")
        JobStatus status,
    @Schema(
            description =
                "Tổng số dòng CSV sẽ import — null cho tới khi PROCESSING xác định được tổng số"
                    + " (không dùng để tính % nếu null).",
            nullable = true,
            example = "5000")
        Long totalUnits,
    @Schema(
            description =
                "Số dòng đã xử lý — dùng làm tử số khi tính % tiến độ (processedUnits/totalUnits).",
            example = "1230")
        long processedUnits,
    @Schema(
            description =
                "Object key file báo cáo lỗi import (nếu có dòng lỗi), chỉ có giá trị khi status ="
                    + " DONE.",
            nullable = true,
            example = "import-error-reports/9f8c1e2a-....csv")
        String resultLocation,
    @Schema(
            description = "Thông báo lỗi, chỉ có giá trị khi status = FAILED.",
            nullable = true,
            example = "File CSV không đúng định dạng")
        String errorMessage,
    @Schema(description = "Thời điểm tạo job.") Instant createdAt,
    @Schema(
            description =
                "Thời điểm cập nhật gần nhất (mỗi lần status/progress/heartbeat thay đổi).")
        Instant updatedAt) {

  public static ImportJobResponse from(Job job) {
    return new ImportJobResponse(
        job.getId(),
        job.getStatus(),
        job.getTotalUnits(),
        job.getProcessedUnits(),
        job.getResultLocation(),
        job.getErrorMessage(),
        job.getCreatedAt(),
        job.getUpdatedAt());
  }
}
