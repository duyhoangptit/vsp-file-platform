package com.company.platform.shared.audit.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record AuditPageResponse<T>(
    List<T> items,
    @Schema(description = "Truyền vào ?cursor= để lấy trang sau; null = trang cuối")
        String nextCursor) {}
