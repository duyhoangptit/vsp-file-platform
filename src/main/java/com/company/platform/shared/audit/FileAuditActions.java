package com.company.platform.shared.audit;

/** Action / resource type của audit_logs dùng cho lịch sử file trong admin API. */
public final class FileAuditActions {

  public static final String UPLOAD_COMPLETED = "FILE_UPLOAD_COMPLETED";
  public static final String DOWNLOAD_URL_ISSUED = "FILE_DOWNLOAD_URL_ISSUED";
  public static final String DOWNLOADED_INTERNAL = "FILE_DOWNLOADED_INTERNAL";
  public static final String VIRUS_SCAN_GLOBAL_UPDATED = "VIRUS_SCAN_GLOBAL_UPDATED";
  public static final String FILE_CATEGORY_UPDATED = "FILE_CATEGORY_UPDATED";

  public static final String RESOURCE_FILE_ASSET = "FILE_ASSET";
  public static final String RESOURCE_FILE_CATEGORY = "FILE_CATEGORY";
  public static final String RESOURCE_FILE_PLATFORM_SETTING = "FILE_PLATFORM_SETTING";

  private FileAuditActions() {}
}
