package com.company.platform.file.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileAssetRepository extends JpaRepository<FileAsset, UUID> {

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE FileAsset f SET f.storageStatus = :newStatus, f.updatedAt = :now "
          + "WHERE f.id = :id AND f.storageStatus = :expectedStatus")
  int casUpdateStorageStatus(
      @Param("id") UUID id,
      @Param("expectedStatus") StorageStatus expectedStatus,
      @Param("newStatus") StorageStatus newStatus,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE FileAsset f SET f.storageStatus = :newStatus, f.checksumSha256 = :checksum, "
          + "f.updatedAt = :now WHERE f.id = :id AND f.storageStatus = :expectedStatus")
  int casUpdateStorageStatusWithChecksum(
      @Param("id") UUID id,
      @Param("expectedStatus") StorageStatus expectedStatus,
      @Param("newStatus") StorageStatus newStatus,
      @Param("checksum") String checksum,
      @Param("now") Instant now);
}
