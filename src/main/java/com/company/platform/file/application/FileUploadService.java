package com.company.platform.file.application;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryNotFoundException;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.domain.FileValidationException;
import com.company.platform.file.domain.FileVerificationFailedException;
import com.company.platform.file.domain.InvalidFileAssetStateException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import io.minio.StatObjectResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileUploadService {

  private static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(15);

  private final FileCategoryRepository fileCategoryRepository;
  private final FileAssetRepository fileAssetRepository;
  private final MinioStorageService minioStorageService;

  public FileUploadService(
      FileCategoryRepository fileCategoryRepository,
      FileAssetRepository fileAssetRepository,
      MinioStorageService minioStorageService) {
    this.fileCategoryRepository = fileCategoryRepository;
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
  }

  @Transactional
  public FileUploadInitiation initiateUpload(InitiateFileUploadCommand command) {
    FileCategory category =
        fileCategoryRepository
            .findById(command.categoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(command.categoryCode()));

    validate(category, command);

    minioStorageService.ensureBucketExists(category.getBucketName());

    UUID fileAssetId = UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();

    FileAsset fileAsset =
        new FileAsset(
            fileAssetId,
            category.getCode(),
            category.getBucketName(),
            objectKey,
            command.originalFilename(),
            command.mimeType(),
            command.sizeBytes(),
            command.ownerType(),
            command.ownerId(),
            command.uploadedBy());
    fileAssetRepository.save(fileAsset);

    String uploadUrl =
        minioStorageService.generatePresignedPutUrl(
            category.getBucketName(), objectKey, UPLOAD_URL_EXPIRY);

    return new FileUploadInitiation(fileAssetId, uploadUrl, Instant.now().plus(UPLOAD_URL_EXPIRY));
  }

  /**
   * CAS 2 bước theo mục 5 (chưa bật virus scan nên đi thẳng CLEAN): UPLOADING -> UPLOADED_DMZ, xác
   * thực HEAD object trên MinIO, rồi UPLOADED_DMZ -> CLEAN. Idempotent nếu đã CLEAN. checksumSha256
   * là giá trị do client tự tính và gửi kèm (backend không tải lại nội dung để tính lại SHA-256,
   * tránh tải server) — HEAD chỉ xác thực object tồn tại và khớp size đã khai báo.
   */
  @Transactional
  public FileAsset confirmUpload(UUID fileAssetId, String checksumSha256) {
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

    return fileAssetRepository
        .findById(fileAssetId)
        .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
  }

  private void validate(FileCategory category, InitiateFileUploadCommand command) {
    if (!category.getAllowedMimeTypes().contains(command.mimeType())) {
      throw new FileValidationException(
          "Mime type %s không được phép cho category %s"
              .formatted(command.mimeType(), command.categoryCode()));
    }
    if (command.sizeBytes() > category.getMaxSizeBytes()) {
      throw new FileValidationException(
          "Kích thước %d vượt quá giới hạn %d byte của category %s"
              .formatted(command.sizeBytes(), category.getMaxSizeBytes(), command.categoryCode()));
    }
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
