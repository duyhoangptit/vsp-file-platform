package com.company.platform.file.api;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.shared.audit.FileAssetSummary;
import com.company.platform.shared.audit.FileAssetSummaryLookup;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Cung cấp tên/kiểu/dung lượng file cho lịch sử download (shared/audit), kể cả file đã xoá mềm. */
@Component
public class FileAssetSummaryLookupAdapter implements FileAssetSummaryLookup {

  private final FileAssetRepository fileAssetRepository;

  public FileAssetSummaryLookupAdapter(FileAssetRepository fileAssetRepository) {
    this.fileAssetRepository = fileAssetRepository;
  }

  @Override
  public Map<UUID, FileAssetSummary> findSummaries(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return fileAssetRepository.findAllById(ids).stream()
        .map(FileAssetSummaryLookupAdapter::toSummary)
        .collect(Collectors.toMap(FileAssetSummary::id, Function.identity()));
  }

  private static FileAssetSummary toSummary(FileAsset a) {
    return new FileAssetSummary(
        a.getId(), a.getOriginalFilename(), a.getMimeType(), a.getSizeBytes(), a.getCategoryCode());
  }
}
