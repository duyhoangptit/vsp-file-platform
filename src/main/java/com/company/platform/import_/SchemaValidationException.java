package com.company.platform.import_;

public class SchemaValidationException extends RuntimeException {

  private final Long rowNumber;
  private final String reason;

  public SchemaValidationException(long rowNumber, String reason) {
    super("Dòng %d không hợp lệ: %s".formatted(rowNumber, reason));
    this.rowNumber = rowNumber;
    this.reason = reason;
  }

  public SchemaValidationException(String reason) {
    super(reason);
    this.rowNumber = null;
    this.reason = reason;
  }

  /** Null khi lỗi ở cấp header (không gắn với 1 dòng dữ liệu cụ thể). */
  public Long getRowNumber() {
    return rowNumber;
  }

  /**
   * Lý do thuần, không kèm tiền tố "Dòng N không hợp lệ" như getMessage() — dùng để build báo cáo.
   */
  public String getReason() {
    return reason;
  }
}
