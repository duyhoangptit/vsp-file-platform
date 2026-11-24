package com.company.platform.shared.audit;

/**
 * PRESIGNED = link tải qua /api/files|/api/templates (kể cả lượt bị từ chối); INTERNAL = API nội
 * bộ.
 */
public enum DownloadChannel {
  PRESIGNED,
  INTERNAL
}
