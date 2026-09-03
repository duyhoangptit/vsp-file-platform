package com.company.platform.file.api;

import com.company.platform.file.application.CompleteMultipartUploadCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

public record CompleteMultipartUploadRequest(
    @Schema(
            description =
                "Danh sách part đã upload (số part + ETag do MinIO trả về lúc PUT part) — server"
                    + " gộp thành object hoàn chỉnh theo thứ tự partNumber.")
        @NotEmpty
        @Valid
        List<PartInput> parts,
    @Schema(
            description = "SHA-256 checksum client tự tính trên toàn bộ file gốc.",
            example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        @NotBlank
        String checksumSha256) {

  public record PartInput(
      @Schema(description = "Số part (1-based).", example = "1") @Positive int partNumber,
      @Schema(description = "ETag MinIO trả về khi PUT part.", example = "\"d41d8cd98f00b204\"")
          @NotBlank
          String etag) {}

  public CompleteMultipartUploadCommand toCommand(UUID fileAssetId) {
    return new CompleteMultipartUploadCommand(
        fileAssetId,
        parts.stream()
            .map(p -> new CompleteMultipartUploadCommand.PartRef(p.partNumber(), p.etag()))
            .toList(),
        checksumSha256);
  }
}
