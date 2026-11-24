package com.company.platform.shared.audit;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Lịch sử download cần tên/kiểu/dung lượng file nhưng {@code shared/audit} không được đọc thẳng
 * file_assets — module {@code file} implement (cùng kiểu {@code ResourceOwnerLookup}). Gồm cả file
 * đã soft-delete; id không tồn tại thì vắng mặt trong map.
 */
public interface FileAssetSummaryLookup {

  Map<UUID, FileAssetSummary> findSummaries(Collection<UUID> ids);
}
