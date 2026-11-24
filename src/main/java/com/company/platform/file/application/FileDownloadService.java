package com.company.platform.file.application;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import com.company.platform.shared.audit.FileAuditActions;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FileDownloadService {

  private static final Duration DOWNLOAD_URL_EXPIRY = Duration.ofMinutes(20);
  private static final String FILE_ASSET_RESOURCE_TYPE = "FILE_ASSET";

  private final FileAssetRepository fileAssetRepository;
  private final MinioStorageService minioStorageService;
  private final AuditLogService auditLogService;

  public FileDownloadService(
      FileAssetRepository fileAssetRepository,
      MinioStorageService minioStorageService,
      AuditLogService auditLogService) {
    this.fileAssetRepository = fileAssetRepository;
    this.minioStorageService = minioStorageService;
    this.auditLogService = auditLogService;
  }

  /**
   * Chỉ sinh presigned GET khi storage_status = CLEAN, đúng bất biến ở mục 5. Ghi {@code
   * FILE_DOWNLOAD_URL_ISSUED} sau khi sinh URL thành công (lịch sử download của admin API).
   *
   * @param via {@code FILE_API} hoặc {@code TEMPLATE_API}
   */
  public FileDownloadResult generateDownloadUrl(UUID fileAssetId, UUID actorId, String via) {
    FileAsset fileAsset = requireCleanFileAsset(fileAssetId);
    FileDownloadResult result = buildResult(fileAsset);
    auditLogService.record(
        actorId,
        FileAuditActions.DOWNLOAD_URL_ISSUED,
        FILE_ASSET_RESOURCE_TYPE,
        fileAssetId,
        Map.of("expiresInSeconds", DOWNLOAD_URL_EXPIRY.toSeconds(), "via", via),
        AuditResult.SUCCESS);
    return result;
  }

  /**
   * Dùng cho các module sở hữu job (VD export) để nhúng thẳng download URL vào response trạng thái
   * job của họ, thay vì bắt client gọi thêm round-trip qua fileAssetId — fileAssetId vẫn ở lại bên
   * trong file module, không lộ ra ngoài qua path này. Cố tình KHÔNG ghi audit download: URL được
   * nhúng lại mỗi lần client poll trạng thái job (không phản ánh lượt tải thật), và export đã có
   * audit trail riêng.
   */
  public Optional<FileDownloadResult> findDownloadUrlByJobId(UUID jobId) {
    return fileAssetRepository
        .findByJobId(jobId)
        .filter(fileAsset -> fileAsset.getStorageStatus() == StorageStatus.CLEAN)
        .map(this::buildResult);
  }

  /**
   * Đọc trực tiếp nội dung byte của file — dành cho API internal (song song với {@link
   * #generateDownloadUrl}, xem design doc 2026-08-08-internal-download-bytes-api-design.md). Caller
   * (Controller) chịu trách nhiệm close() {@code InputStream} trả về. Giữ nguyên bất biến chỉ đọc
   * được khi storage_status = CLEAN, nhất quán với presigned URL. Ghi audit tường minh vì đường này
   * không đi qua {@code @RequiresAccess}/{@code ResourceAccessAuditAspect}.
   */
  public FileBytesResult prepareBytesDownload(UUID fileAssetId) {
    FileAsset fileAsset = requireCleanFileAsset(fileAssetId);
    InputStream content =
        minioStorageService.getObject(fileAsset.getBucketName(), fileAsset.getObjectKey());
    auditLogService.record(
        null,
        "FILE_DOWNLOADED_INTERNAL",
        FILE_ASSET_RESOURCE_TYPE,
        fileAssetId,
        AuditResult.SUCCESS);
    return new FileBytesResult(
        content,
        fileAsset.getOriginalFilename(),
        fileAsset.getMimeType(),
        fileAsset.getSizeBytes());
  }

  private FileAsset requireCleanFileAsset(UUID fileAssetId) {
    FileAsset fileAsset =
        fileAssetRepository
            .findById(fileAssetId)
            .orElseThrow(() -> new FileAssetNotFoundException(fileAssetId));

    if (fileAsset.getStorageStatus() != StorageStatus.CLEAN) {
      throw new FileNotAvailableForDownloadException(fileAssetId, fileAsset.getStorageStatus());
    }
    return fileAsset;
  }

  private FileDownloadResult buildResult(FileAsset fileAsset) {
    String downloadUrl =
        minioStorageService.generatePresignedGetUrl(
            fileAsset.getBucketName(), fileAsset.getObjectKey(), DOWNLOAD_URL_EXPIRY);
    return new FileDownloadResult(downloadUrl, Instant.now().plus(DOWNLOAD_URL_EXPIRY));
  }
}
