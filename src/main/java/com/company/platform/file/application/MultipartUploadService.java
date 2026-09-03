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
import com.company.platform.file.domain.InvalidPartNumberException;
import com.company.platform.file.domain.MultipartNotEnabledException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.file.storage.multipart.MultipartStorageService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MultipartUploadService {

  static final Duration PART_URL_EXPIRY = Duration.ofMinutes(30);
  static final int MAX_S3_PARTS = 10_000;

  private final FileCategoryRepository fileCategoryRepository;
  private final FileAssetRepository fileAssetRepository;
  private final MinioStorageService minioStorageService;
  private final MultipartStorageService multipartStorageService;
  private final FileAssetStatusFinalizer fileAssetStatusFinalizer;

  public MultipartUploadService(
      FileCategoryRepository fileCategoryRepository,
      FileAssetRepository fileAssetRepository,
      MinioStorageService minioStorageService,
      MultipartStorageService multipartStorageService,
      FileAssetStatusFinalizer fileAssetStatusFinalizer) {
    this.fileCategoryRepository = fileCategoryRepository;
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
    this.multipartStorageService = multipartStorageService;
    this.fileAssetStatusFinalizer = fileAssetStatusFinalizer;
  }

  @Transactional
  public MultipartUploadInitiation initiateMultipart(InitiateMultipartUploadCommand command) {
    FileCategory category =
        fileCategoryRepository
            .findById(command.categoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(command.categoryCode()));

    Long partSize = category.getMultipartPartSizeBytes();
    if (partSize == null) {
      throw new MultipartNotEnabledException(category.getCode());
    }
    if (!category.getAllowedMimeTypes().contains(command.mimeType())) {
      throw new FileValidationException(
          "Mime type %s không được phép cho category %s"
              .formatted(command.mimeType(), command.categoryCode()));
    }

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
            0L,
            command.ownerType(),
            command.ownerId(),
            command.uploadedBy(),
            computeExpiresAt(category));

    String uploadId =
        multipartStorageService.createMultipartUpload(
            category.getBucketName(), objectKey, command.mimeType());
    fileAsset.attachMultipartUpload(uploadId);
    fileAssetRepository.save(fileAsset);

    return new MultipartUploadInitiation(fileAssetId, partSize, maxParts(category, partSize));
  }

  @Transactional(readOnly = true)
  public List<PartUrl> generatePartUrls(UUID fileAssetId, List<Integer> partNumbers) {
    FileAsset asset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
    FileCategory category =
        fileCategoryRepository
            .findById(asset.getCategoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(asset.getCategoryCode()));
    Long partSize = category.getMultipartPartSizeBytes();
    if (partSize == null || asset.getMultipartUploadId() == null) {
      throw new MultipartNotEnabledException(asset.getCategoryCode());
    }
    int max = maxParts(category, partSize);

    Instant expiresAt = Instant.now().plus(PART_URL_EXPIRY);
    return partNumbers.stream()
        .map(
            n -> {
              if (n < 1 || n > max) {
                throw new InvalidPartNumberException(n, max);
              }
              String url =
                  multipartStorageService.presignUploadPart(
                      asset.getBucketName(),
                      asset.getObjectKey(),
                      asset.getMultipartUploadId(),
                      n,
                      PART_URL_EXPIRY);
              return new PartUrl(n, url, expiresAt);
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public List<MultipartStorageService.UploadedPart> listUploadedParts(UUID fileAssetId) {
    FileAsset asset = requireOpenMultipart(fileAssetId);
    return multipartStorageService.listParts(
        asset.getBucketName(), asset.getObjectKey(), asset.getMultipartUploadId());
  }

  @Transactional
  public FileAsset completeMultipart(CompleteMultipartUploadCommand cmd) {
    FileAsset asset =
        fileAssetRepository
            .findById(cmd.fileAssetId())
            .orElseThrow(() -> new FileAssetNotFoundException(cmd.fileAssetId()));
    if (asset.getStorageStatus() == StorageStatus.CLEAN) {
      return asset; // idempotent
    }
    String uploadId = asset.getMultipartUploadId();
    if (uploadId == null) {
      throw new InvalidFileAssetStateException(cmd.fileAssetId(), asset.getStorageStatus());
    }
    FileCategory category =
        fileCategoryRepository
            .findById(asset.getCategoryCode())
            .orElseThrow(() -> new FileCategoryNotFoundException(asset.getCategoryCode()));

    multipartStorageService.completeMultipartUpload(
        asset.getBucketName(),
        asset.getObjectKey(),
        uploadId,
        cmd.parts().stream()
            .map(p -> new MultipartStorageService.CompletedPartRef(p.partNumber(), p.etag()))
            .toList());

    long realSize =
        minioStorageService
            .statObject(asset.getBucketName(), asset.getObjectKey())
            .orElseThrow(
                () ->
                    new FileVerificationFailedException(
                        asset.getId(), "object không tồn tại sau khi complete multipart"))
            .size();
    if (realSize > category.getMaxSizeBytes()) {
      multipartStorageService.abortMultipartUpload(
          asset.getBucketName(), asset.getObjectKey(), uploadId);
      fileAssetRepository.softDelete(asset.getId(), Instant.now());
      fileAssetRepository.clearMultipartUploadId(asset.getId(), Instant.now());
      throw new FileVerificationFailedException(
          asset.getId(),
          "kích thước %d vượt trần %d của category"
              .formatted(realSize, category.getMaxSizeBytes()));
    }

    fileAssetRepository.updateSizeBytes(asset.getId(), realSize, Instant.now());
    FileAsset clean = fileAssetStatusFinalizer.finalizeToClean(asset.getId(), cmd.checksumSha256());
    fileAssetRepository.clearMultipartUploadId(asset.getId(), Instant.now());
    return clean;
  }

  @Transactional
  public void abortMultipart(UUID fileAssetId) {
    FileAsset asset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
    String uploadId = asset.getMultipartUploadId();
    if (uploadId != null) {
      multipartStorageService.abortMultipartUpload(
          asset.getBucketName(), asset.getObjectKey(), uploadId);
    }
    fileAssetRepository.softDelete(fileAssetId, Instant.now());
    fileAssetRepository.clearMultipartUploadId(fileAssetId, Instant.now());
  }

  private FileAsset requireOpenMultipart(UUID fileAssetId) {
    FileAsset asset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));
    if (asset.getMultipartUploadId() == null) {
      throw new InvalidFileAssetStateException(fileAssetId, asset.getStorageStatus());
    }
    return asset;
  }

  private int maxParts(FileCategory category, long partSize) {
    long ceil = (category.getMaxSizeBytes() + partSize - 1) / partSize;
    return (int) Math.min(ceil, MAX_S3_PARTS);
  }

  private Instant computeExpiresAt(FileCategory category) {
    Integer retentionDays = category.getRetentionDays();
    return retentionDays == null ? null : Instant.now().plus(Duration.ofDays(retentionDays));
  }
}
