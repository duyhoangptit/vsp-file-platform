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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
  private final FileAssetStatusFinalizer fileAssetStatusFinalizer;

  public FileUploadService(
      FileCategoryRepository fileCategoryRepository,
      FileAssetRepository fileAssetRepository,
      MinioStorageService minioStorageService,
      FileAssetStatusFinalizer fileAssetStatusFinalizer) {
    this.fileCategoryRepository = fileCategoryRepository;
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
    this.fileAssetStatusFinalizer = fileAssetStatusFinalizer;
  }

  @Transactional
  public FileUploadInitiation initiateUpload(InitiateFileUploadCommand command) {
    FileCategory category =
        fileCategoryRepository
            .findById(command.categoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(command.categoryCode()));

    validate(category, command);

    minioStorageService.ensureBucketExists(category.getBucketName(), category.getRetentionDays());

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
            command.uploadedBy(),
            computeExpiresAt(category));
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
    return fileAssetStatusFinalizer.finalizeToClean(fileAssetId, checksumSha256);
  }

  /**
   * Đăng ký 1 file do chính server sinh ra (VD export result), khác với luồng presigned PUT do
   * client thực hiện ({@link #initiateUpload}/{@link #confirmUpload}) — không cần bước chờ client
   * upload rồi confirm, vì server đã có sẵn nội dung + checksum. Đi qua đúng chuỗi CAS UPLOADING ->
   * UPLOADED_DMZ -> CLEAN như confirmUpload để không phải sửa lại logic khi bật virus scan sau này
   * (mục 5 design doc).
   */
  @Transactional
  public FileAsset registerServerGeneratedFile(RegisterGeneratedFileCommand command) {
    FileCategory category =
        fileCategoryRepository
            .findById(command.categoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(command.categoryCode()));

    minioStorageService.ensureBucketExists(category.getBucketName(), category.getRetentionDays());

    UUID fileAssetId = UUID.randomUUID();
    String objectKey = UUID.randomUUID().toString();
    long sizeBytes = fileSize(command.localFile());

    FileAsset fileAsset =
        new FileAsset(
            fileAssetId,
            category.getCode(),
            category.getBucketName(),
            objectKey,
            command.originalFilename(),
            command.mimeType(),
            sizeBytes,
            command.ownerType(),
            command.ownerId(),
            command.uploadedBy(),
            command.jobId(),
            computeExpiresAt(category));
    fileAssetRepository.save(fileAsset);

    minioStorageService.uploadFile(
        category.getBucketName(), objectKey, command.localFile(), command.mimeType());

    int toDmz =
        fileAssetRepository.casUpdateStorageStatus(
            fileAssetId, StorageStatus.UPLOADING, StorageStatus.UPLOADED_DMZ, Instant.now());
    if (toDmz != 1) {
      throw new InvalidFileAssetStateException(fileAssetId, StorageStatus.UPLOADING);
    }

    verifyUploadedObject(fileAsset);

    int toClean =
        fileAssetRepository.casUpdateStorageStatusWithChecksum(
            fileAssetId,
            StorageStatus.UPLOADED_DMZ,
            StorageStatus.CLEAN,
            command.checksumSha256(),
            Instant.now());
    if (toClean != 1) {
      throw new InvalidFileAssetStateException(fileAssetId, StorageStatus.UPLOADED_DMZ);
    }

    return fileAssetRepository
        .findById(fileAssetId)
        .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
  }

  // retentionDays null = giữ file vĩnh viễn, không set expiresAt (mục 10 design doc) — cùng
  // nguồn retentionDays đã dùng để set MinIO bucket lifecycle rule (MinioStorageService).
  private Instant computeExpiresAt(FileCategory category) {
    Integer retentionDays = category.getRetentionDays();
    return retentionDays == null ? null : Instant.now().plus(Duration.ofDays(retentionDays));
  }

  private long fileSize(Path localFile) {
    try {
      return Files.size(localFile);
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được kích thước file " + localFile, e);
    }
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
