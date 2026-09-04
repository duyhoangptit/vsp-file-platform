package com.company.platform.file.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PartUrlsRequest(
    @Schema(
            description =
                "Danh sách số part (1-based) client muốn xin presigned PUT URL — thường xin theo lô"
                    + " để retry được từng part.",
            example = "[1, 2, 3]")
        @NotEmpty
        List<Integer> partNumbers) {}
