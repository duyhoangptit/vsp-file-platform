package com.company.platform.file.api.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record FileAdminPageResponse(
    List<FileAdminItemResponse> items,
    @Schema(description = "Truyền vào ?cursor= để lấy trang sau; null = trang cuối")
        String nextCursor) {}
