package com.company.platform.file.application;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FileDownloadService {

  private static final Duration DOWNLOAD_URL_EXPIRY = Duration.ofMinutes(20);

  private final FileAssetRepository fileAssetRepository;
  private final MinioStorageService minioStorageService;

  public FileDownloadService(
      FileAssetRepository fileAssetRepository, MinioStorageService minioStorageService) {
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
  }

  /** Chỉ sinh presigned GET khi storage_status = CLEAN, đúng bất biến ở mục 5. */
  public FileDownloadResult generateDownloadUrl(UUID fileAssetId) {
    FileAsset fileAsset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));

    if (fileAsset.getStorageStatus() != StorageStatus.CLEAN) {
      throw new FileNotAvailableForDownloadException(fileAssetId, fileAsset.getStorageStatus());
    }

    return buildResult(fileAsset);
  }

  /**
   * Dùng cho các module sở hữu job (VD export) để nhúng thẳng download URL vào response trạng thái
   * job của họ, thay vì bắt client gọi thêm round-trip qua fileAssetId — fileAssetId vẫn ở lại bên
   * trong file module, không lộ ra ngoài qua path này.
   */
  public Optional<FileDownloadResult> findDownloadUrlByJobId(UUID jobId) {
    return fileAssetRepository
        .findByJobId(jobId)
        .filter(fileAsset -> fileAsset.getStorageStatus() == StorageStatus.CLEAN)
        .map(this::buildResult);
  }

  private FileDownloadResult buildResult(FileAsset fileAsset) {
    String downloadUrl =
        minioStorageService.generatePresignedGetUrl(
            fileAsset.getBucketName(), fileAsset.getObjectKey(), DOWNLOAD_URL_EXPIRY);
    return new FileDownloadResult(downloadUrl, Instant.now().plus(DOWNLOAD_URL_EXPIRY));
  }
}
