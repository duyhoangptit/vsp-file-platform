package com.company.platform.template.api;

import com.company.platform.template.domain.FileTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record FileTemplateResponse(
    @Schema(description = "ID version template vừa tạo.") UUID id,
    @Schema(description = "Cụm nghiệp vụ lớn.", example = "MEMBER") String category,
    @Schema(description = "Mã function.", example = "MEMBER_IMPORT") String templateCode,
    @Schema(description = "Version vừa tạo, tăng dần theo (category, templateCode).", example = "1")
        int version,
    @Schema(description = "Mô tả version này.") String description,
    @Schema(description = "Thời điểm tạo version này.") Instant createdAt) {

  public static FileTemplateResponse from(FileTemplate fileTemplate) {
    return new FileTemplateResponse(
        fileTemplate.getId(),
        fileTemplate.getCategory(),
        fileTemplate.getTemplateCode(),
        fileTemplate.getVersion(),
        fileTemplate.getDescription(),
        fileTemplate.getCreatedAt());
  }
}
