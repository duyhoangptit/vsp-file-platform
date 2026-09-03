package com.company.platform.file.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileAssetRepository extends JpaRepository<FileAsset, UUID> {

  Optional<FileAsset> findByJobId(UUID jobId);

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

  // Native SQL vì cần LIMIT trong subquery (JPQL không hỗ trợ) — chỉ xử lý tối đa batchSize
  // dòng của 1 category/lần gọi (mục 10 design doc: dọn metadata sau khi file vật lý đã bị
  // MinIO lifecycle xoá). Lọc đúng thứ tự cột của index idx_file_assets_category_status_expiry
  // (category_code, storage_status, expires_at) để tránh full scan trên bảng lớn.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE file_assets SET deleted_at = :now, updated_at = :now WHERE id IN ("
              + "SELECT id FROM file_assets WHERE category_code = :categoryCode "
              + "AND storage_status = 'CLEAN' AND expires_at < :now AND deleted_at IS NULL "
              + "ORDER BY id LIMIT :batchSize)",
      nativeQuery = true)
  int softDeleteExpiredBatch(
      @Param("categoryCode") String categoryCode,
      @Param("now") Instant now,
      @Param("batchSize") int batchSize);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE FileAsset f SET f.multipartUploadId = null, f.updatedAt = :now WHERE f.id = :id")
  int clearMultipartUploadId(@Param("id") UUID id, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE FileAsset f SET f.sizeBytes = :sizeBytes, f.updatedAt = :now "
          + "WHERE f.id = :id AND f.storageStatus = com.company.platform.file.domain.StorageStatus.UPLOADING")
  int updateSizeBytes(
      @Param("id") UUID id, @Param("sizeBytes") long sizeBytes, @Param("now") Instant now);
}
