package com.company.platform.file.application;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileVerificationFailedException;
import com.company.platform.file.domain.InvalidFileAssetStateException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.FileAuditActions;
import io.minio.StatObjectResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Verify + CAS về CLEAN" dùng chung cho confirm luồng presigned PUT đơn (FileUploadService) và
 * complete luồng multipart (MultipartUploadService) — cùng chuỗi UPLOADING -> UPLOADED_DMZ -> CLEAN
 * (mục 5 design doc), idempotent nếu đã CLEAN.
 */
@Component
public class FileAssetStatusFinalizer {

  private final FileAssetRepository fileAssetRepository;
  private final MinioStorageService minioStorageService;
  private final AuditLogService auditLogService;

  public FileAssetStatusFinalizer(
      FileAssetRepository fileAssetRepository,
      MinioStorageService minioStorageService,
      AuditLogService auditLogService) {
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
    this.auditLogService = auditLogService;
  }

  /**
   * Ghi {@code FILE_UPLOAD_COMPLETED} — chỉ gọi ngay sau khi CAS {@code UPLOADED_DMZ -> CLEAN}
   * thành công (không ghi ở nhánh idempotent đã CLEAN), cùng transaction với CAS.
   *
   * @param source {@code USER_UPLOAD} hoặc {@code JOB_GENERATED}
   */
  public void recordUploadCompleted(FileAsset fileAsset, UUID actorId, String source) {
    auditLogService.record(
        actorId,
        FileAuditActions.UPLOAD_COMPLETED,
        FileAuditActions.RESOURCE_FILE_ASSET,
        fileAsset.getId(),
        Map.of(
            "sizeBytes", fileAsset.getSizeBytes(),
            "mimeType", fileAsset.getMimeType(),
            "categoryCode", fileAsset.getCategoryCode(),
            "source", source),
        AuditResult.SUCCESS);
  }

  /**
   * CAS 2 bước theo mục 5 (chưa bật virus scan nên đi thẳng CLEAN): UPLOADING -> UPLOADED_DMZ, xác
   * thực HEAD object trên MinIO, rồi UPLOADED_DMZ -> CLEAN. Idempotent nếu đã CLEAN. checksumSha256
   * là giá trị do client tự tính và gửi kèm (backend không tải lại nội dung để tính lại SHA-256,
   * tránh tải server) — HEAD chỉ xác thực object tồn tại và khớp size đã khai báo.
   */
  @Transactional
  public FileAsset finalizeToClean(UUID fileAssetId, String checksumSha256, UUID actorId) {
    FileAsset fileAsset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));

    if (fileAsset.getStorageStatus() == StorageStatus.CLEAN) {
      return fileAsset;
    }
    if (fileAsset.getStorageStatus() != StorageStatus.UPLOADING
        && fileAsset.getStorageStatus() != StorageStatus.UPLOADED_DMZ) {
      throw new InvalidFileAssetStateException(fileAssetId, fileAsset.getStorageStatus());
    }

    if (fileAsset.getStorageStatus() == StorageStatus.UPLOADING) {
      int updated =
          fileAssetRepository.casUpdateStorageStatus(
              fileAssetId, StorageStatus.UPLOADING, StorageStatus.UPLOADED_DMZ, Instant.now());
      if (updated != 1) {
        throw new InvalidFileAssetStateException(fileAssetId, fileAsset.getStorageStatus());
      }
    }

    verifyUploadedObject(fileAsset);

    int updated =
        fileAssetRepository.casUpdateStorageStatusWithChecksum(
            fileAssetId,
            StorageStatus.UPLOADED_DMZ,
            StorageStatus.CLEAN,
            checksumSha256,
            Instant.now());
    if (updated != 1) {
      throw new InvalidFileAssetStateException(fileAssetId, fileAsset.getStorageStatus());
    }

    FileAsset clean =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
    recordUploadCompleted(clean, actorId, "USER_UPLOAD");
    return clean;
  }

  private void verifyUploadedObject(FileAsset fileAsset) {
    StatObjectResponse stat =
        minioStorageService
            .statObject(fileAsset.getBucketName(), fileAsset.getObjectKey())
            .orElseThrow(
                () ->
                    new FileVerificationFailedException(
                        fileAsset.getId(),
                        "Không tìm thấy object trên storage, có thể upload chưa hoàn tất"));
    if (stat.size() != fileAsset.getSizeBytes()) {
      throw new FileVerificationFailedException(
          fileAsset.getId(),
          "Kích thước không khớp: khai báo=%d, thực tế=%d"
              .formatted(fileAsset.getSizeBytes(), stat.size()));
    }
  }
}
