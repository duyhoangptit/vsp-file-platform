package com.company.platform.shared.audit;

import com.company.platform.shared.pagination.TimeRange;
import java.util.UUID;

public record DownloadQuery(
    TimeRange range, UUID actorId, UUID fileAssetId, AuditResult result, DownloadChannel channel) {

  public static Builder builder(TimeRange range) {
    return new Builder(range);
  }

  public static final class Builder {
    private final TimeRange range;
    private UUID actorId;
    private UUID fileAssetId;
    private AuditResult result;
    private DownloadChannel channel;

    private Builder(TimeRange range) {
      this.range = range;
    }

    public Builder actorId(UUID v) {
      this.actorId = v;
      return this;
    }

    public Builder fileAssetId(UUID v) {
      this.fileAssetId = v;
      return this;
    }

    public Builder result(AuditResult v) {
      this.result = v;
      return this;
    }

    public Builder channel(DownloadChannel v) {
      this.channel = v;
      return this;
    }

    public DownloadQuery build() {
      return new DownloadQuery(range, actorId, fileAssetId, result, channel);
    }
  }
}
