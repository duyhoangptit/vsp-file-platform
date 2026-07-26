package com.company.platform.export.application;

import com.company.platform.job.domain.Job;

/**
 * downloadUrl chỉ có giá trị khi job DONE và file kết quả đã CLEAN — null trong mọi trường hợp khác
 * (đang chạy, lỗi, hoặc file chưa scan xong).
 */
public record ExportJobDetail(Job job, String downloadUrl) {}
