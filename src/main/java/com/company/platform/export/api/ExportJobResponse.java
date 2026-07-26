package com.company.platform.export.api;

import com.company.platform.export.application.ExportJobDetail;
import com.company.platform.job.domain.Job;
import com.company.platform.job.domain.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(
    description =
        "Trạng thái job export CSV, dùng để FE poll và dựng progress bar (mục 11 design doc).")
public record ExportJobResponse(
    @Schema(description = "ID job, dùng để gọi GET /exports/{id} poll trạng thái.") UUID id,
    @Schema(
            description =
                "PENDING: đang chờ xử lý. PROCESSING: đang chạy (poll processedUnits/totalUnits để"
                    + " hiển thị %). DONE: xong, xem resultLocation. FAILED: lỗi, xem errorMessage."
                    + " EXPIRED: đã dọn dẹp do hết hạn retention.")
        JobStatus status,
    @Schema(
            description =
                "Tổng số bản ghi sẽ export — null cho tới khi PROCESSING xác định được tổng số"
                    + " (không dùng để tính % nếu null).",
            nullable = true,
            example = "10000")
        Long totalUnits,
    @Schema(
            description =
                "Số bản ghi đã xử lý — dùng làm tử số khi tính % tiến độ (processedUnits/totalUnits).",
            example = "4200")
        long processedUnits,
    @Schema(
            description =
                "Object key nội bộ của file kết quả trên MinIO — không dùng trực tiếp để tải file,"
                    + " chỉ có giá trị khi status = DONE. Dùng downloadUrl để tải file.",
            nullable = true,
            example = "34bdfb2b-7f81-47ac-823e-38f365cdb619")
        String resultLocation,
    @Schema(
            description =
                "Presigned GET URL để tải file kết quả, chỉ có giá trị khi status = DONE và file"
                    + " đã ở trạng thái CLEAN. Thời hạn ngắn (xem GET"
                    + " /api/files/{fileAssetId}/download-url) — không lưu lại URL này, luôn poll"
                    + " lại endpoint này khi cần tải.",
            nullable = true,
            example = "https://minio:9000/export-results/9f8c1e2a-....csv?X-Amz-Signature=...")
        String downloadUrl,
    @Schema(
            description = "Thông báo lỗi, chỉ có giá trị khi status = FAILED.",
            nullable = true,
            example = "Kết nối database bị timeout")
        String errorMessage,
    @Schema(description = "Thời điểm tạo job.") Instant createdAt,
    @Schema(
            description =
                "Thời điểm cập nhật gần nhất (mỗi lần status/progress/heartbeat thay đổi).")
        Instant updatedAt) {

  public static ExportJobResponse from(ExportJobDetail detail) {
    Job job = detail.job();
    return new ExportJobResponse(
        job.getId(),
        job.getStatus(),
        job.getTotalUnits(),
        job.getProcessedUnits(),
        job.getResultLocation(),
        detail.downloadUrl(),
        job.getErrorMessage(),
        job.getCreatedAt(),
        job.getUpdatedAt());
  }

  public static ExportJobResponse from(Job job) {
    return from(new ExportJobDetail(job, null));
  }
}
