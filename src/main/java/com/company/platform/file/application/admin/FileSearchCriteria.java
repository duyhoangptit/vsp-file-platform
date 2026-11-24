package com.company.platform.file.application.admin;

import com.company.platform.file.domain.ScanStatus;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.shared.pagination.TimeRange;
import java.util.Set;
import java.util.UUID;

/**
 * Bộ lọc danh sách file admin. {@code mimeType} kết thúc bằng {@code /} = lọc theo prefix (VD
 * {@code image/}); {@code filename} = chứa chuỗi, không phân biệt hoa thường.
 */
public record FileSearchCriteria(
    TimeRange range,
    String categoryCode,
    Set<StorageStatus> storageStatuses,
    ScanStatus scanStatus,
    String mimeType,
    UUID uploadedBy,
    String ownerType,
    UUID ownerId,
    String filename,
    FileSource source,
    boolean includeDeleted) {

  public static Builder builder(TimeRange range) {
    return new Builder(range);
  }

  public static final class Builder {
    private final TimeRange range;
    private String categoryCode;
    private Set<StorageStatus> storageStatuses = Set.of();
    private ScanStatus scanStatus;
    private String mimeType;
    private UUID uploadedBy;
    private String ownerType;
    private UUID ownerId;
    private String filename;
    private FileSource source;
    private boolean includeDeleted;

    private Builder(TimeRange range) {
      this.range = range;
    }

    public Builder categoryCode(String v) {
      this.categoryCode = v;
      return this;
    }

    public Builder storageStatuses(Set<StorageStatus> v) {
      this.storageStatuses = v == null ? Set.of() : Set.copyOf(v);
      return this;
    }

    public Builder scanStatus(ScanStatus v) {
      this.scanStatus = v;
      return this;
    }

    public Builder mimeType(String v) {
      this.mimeType = v;
      return this;
    }

    public Builder uploadedBy(UUID v) {
      this.uploadedBy = v;
      return this;
    }

    public Builder ownerType(String v) {
      this.ownerType = v;
      return this;
    }

    public Builder ownerId(UUID v) {
      this.ownerId = v;
      return this;
    }

    public Builder filename(String v) {
      this.filename = v;
      return this;
    }

    public Builder source(FileSource v) {
      this.source = v;
      return this;
    }

    public Builder includeDeleted(boolean v) {
      this.includeDeleted = v;
      return this;
    }

    public FileSearchCriteria build() {
      return new FileSearchCriteria(
          range,
          blankToNull(categoryCode),
          storageStatuses,
          scanStatus,
          blankToNull(mimeType),
          uploadedBy,
          blankToNull(ownerType),
          ownerId,
          blankToNull(filename),
          source,
          includeDeleted);
    }

    private static String blankToNull(String v) {
      return v == null || v.isBlank() ? null : v.trim();
    }
  }
}
