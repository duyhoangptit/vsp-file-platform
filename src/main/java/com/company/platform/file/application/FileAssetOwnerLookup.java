package com.company.platform.file.application;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.shared.auth.ResourceOwnerLookup;
import com.company.platform.shared.auth.ResourceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FileAssetOwnerLookup implements ResourceOwnerLookup {

  private final FileAssetRepository fileAssetRepository;

  public FileAssetOwnerLookup(FileAssetRepository fileAssetRepository) {
    this.fileAssetRepository = fileAssetRepository;
  }

  @Override
  public ResourceType supports() {
    return ResourceType.FILE_ASSET;
  }

  @Override
  public Optional<UUID> findOwnerId(String resourceId) {
    UUID id;
    try {
      id = UUID.fromString(resourceId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return fileAssetRepository.findById(id).map(FileAsset::getOwnerId);
  }
}
