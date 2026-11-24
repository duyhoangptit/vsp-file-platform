package com.company.platform.file.application.admin;

import java.util.List;

/**
 * Field null = không đổi. {@code retentionDaysPresent=true} + {@code retentionDays=null} = xoá
 * retention (giữ file vĩnh viễn).
 */
public record UpdateFileCategoryCommand(
    long version,
    Boolean requireVirusScan,
    List<String> allowedMimeTypes,
    Long maxSizeBytes,
    Integer retentionDays,
    boolean retentionDaysPresent) {}
