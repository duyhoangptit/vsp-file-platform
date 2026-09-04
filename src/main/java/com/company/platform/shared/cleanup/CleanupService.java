package com.company.platform.shared.cleanup;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.storage.multipart.MultipartStorageService;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.shared.lock.AdvisoryLockService;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mục 10 design doc (Retention & Cleanup): dọn metadata file_assets sau khi file vật lý đã bị MinIO
 * lifecycle xoá, và chuyển jobs DONE quá hạn sang EXPIRED (mục 4). Chỉ 1 instance được chạy tại 1
 * thời điểm nhờ advisory lock (mục 6.4-style resource guard, tránh 2 instance cùng xử lý trùng 1
 * tick).
 */
@Service
@EnableConfigurationProperties(CleanupProperties.class)
public class CleanupService {

  // Hằng số riêng cho cleanup job — chưa dùng advisory lock ở đâu khác trong hệ thống.
  private static final long CLEANUP_LOCK_KEY = 725817L;

  private final FileCategoryRepository fileCategoryRepository;
  private final FileAssetRepository fileAssetRepository;
  private final JobRepository jobRepository;
  private final MultipartStorageService multipartStorageService;
  private final AdvisoryLockService advisoryLockService;
  private final CleanupProperties properties;

  public CleanupService(
      FileCategoryRepository fileCategoryRepository,
      FileAssetRepository fileAssetRepository,
      JobRepository jobRepository,
      MultipartStorageService multipartStorageService,
      AdvisoryLockService advisoryLockService,
      CleanupProperties properties) {
    this.fileCategoryRepository = fileCategoryRepository;
    this.fileAssetRepository = fileAssetRepository;
    this.jobRepository = jobRepository;
    this.multipartStorageService = multipartStorageService;
    this.advisoryLockService = advisoryLockService;
    this.properties = properties;
  }

  @Transactional
  public void runCleanup() {
    if (!advisoryLockService.tryLock(CLEANUP_LOCK_KEY)) {
      // Instance khác đang/đã xử lý tick này — bỏ qua, tick sau (fixedDelay) sẽ thử lại.
      return;
    }
    cleanupExpiredFileAssets();
    expireDoneJobs();
    abortStaleMultipartUploads();
  }

  // Phiên multipart client bỏ dở (mục 8.1 design doc): abort phía storage để MinIO thu hồi part
  // đã upload, rồi soft-delete metadata + xoá multipart_upload_id. Nếu abort ném lỗi (upload đã
  // biến mất phía storage) vẫn dọn metadata bên dưới — không để dòng kẹt UPLOADING mãi.
  private void abortStaleMultipartUploads() {
    Instant threshold = Instant.now().minus(properties.multipartUploadTimeout());
    for (FileAsset asset :
        fileAssetRepository.findStaleMultipartUploads(threshold, properties.batchSize())) {
      try {
        multipartStorageService.abortMultipartUpload(
            asset.getBucketName(), asset.getObjectKey(), asset.getMultipartUploadId());
      } catch (RuntimeException e) {
        // upload đã biến mất phía storage — bỏ qua, vẫn dọn metadata bên dưới.
      }
      Instant now = Instant.now();
      fileAssetRepository.softDelete(asset.getId(), now);
      fileAssetRepository.clearMultipartUploadId(asset.getId(), now);
    }
  }

  // Mỗi category chỉ xử lý tối đa batchSize dòng/lần gọi — bounded, phần tồn đọng được dọn
  // tiếp ở tick sau, tự hội tụ theo thời gian thay vì loop-tới-hết trong 1 transaction.
  private void cleanupExpiredFileAssets() {
    Instant now = Instant.now();
    for (FileCategory category : fileCategoryRepository.findAllByRetentionDaysIsNotNull()) {
      fileAssetRepository.softDeleteExpiredBatch(category.getCode(), now, properties.batchSize());
    }
  }

  private void expireDoneJobs() {
    Instant now = Instant.now();
    Instant threshold = now.minus(properties.jobDoneRetention());
    jobRepository.expireDoneBatch(threshold, now, properties.batchSize());
  }
}
