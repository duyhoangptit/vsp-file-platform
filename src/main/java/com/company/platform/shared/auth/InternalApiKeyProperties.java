package com.company.platform.shared.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Secret key dùng chung cho mọi service nội bộ gọi vào các API dưới prefix
 * {@code /api/internal/**} (VD downloadFileBytes) — xem mục 3/5 design doc
 * 2026-08-08-internal-download-bytes-api-design.md. Cố tình KHÔNG có giá trị default thật trong
 * application.yml (chỉ blank), bắt buộc set qua APP_INTERNAL_API_KEY ở môi trường thật.
 *
 * <p>Không validate @NotBlank ở đây (context sẽ fail-fast lúc start, ảnh hưởng mọi
 * @SpringBootTest hiện có không liên quan tới endpoint internal) — thay vào đó
 * {@link InternalApiKeyInterceptor} tự chặn (fail-closed, từ chối mọi request) khi key rỗng, xem
 * javadoc ở đó.
 */
@ConfigurationProperties(prefix = "app.internal-api")
public record InternalApiKeyProperties(String key) {}
