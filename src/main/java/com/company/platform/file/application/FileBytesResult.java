package com.company.platform.file.application;

import java.io.InputStream;

/**
 * Caller chịu trách nhiệm close() {@code content} sau khi đọc/ghi xong — giống hợp đồng của {@link
 * com.company.platform.file.storage.MinioStorageService#getObject}.
 */
public record FileBytesResult(
    InputStream content, String originalFilename, String mimeType, long sizeBytes) {}
