package com.company.platform.export.api;

/**
 * idempotencyKey optional — dùng để chặn double-submit (mục 11 design doc), không có filter nào
 * khác vì business case đầu tiên export toàn bộ app_users.
 */
public record CreateExportUserCsvRequest(String idempotencyKey) {}
