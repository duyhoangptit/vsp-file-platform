package com.company.platform.shared.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.file.storage.multipart.MultipartStorageService;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.shared.lock.AdvisoryLockService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CleanupServiceTest {

  private final FileCategoryRepository fileCategoryRepository = mock(FileCategoryRepository.class);
  private final FileAssetRepository fileAssetRepository = mock(FileAssetRepository.class);
  private final JobRepository jobRepository = mock(JobRepository.class);
  private final MultipartStorageService multipartStorageService =
      mock(MultipartStorageService.class);
  private final AdvisoryLockService advisoryLockService = mock(AdvisoryLockService.class);
  private final CleanupProperties properties =
      new CleanupProperties(Duration.ofMinutes(15), 500, Duration.ofDays(30), Duration.ofHours(2));
  private final CleanupService cleanupService =
      new CleanupService(
          fileCategoryRepository,
          fileAssetRepository,
          jobRepository,
          multipartStorageService,
          advisoryLockService,
          properties);

  @Test
  void should_doNothing_when_lockNotAcquired() {
    when(advisoryLockService.tryLock(anyLong())).thenReturn(false);

    cleanupService.runCleanup();

    verify(fileCategoryRepository, never()).findAllByRetentionDaysIsNotNull();
    verify(fileAssetRepository, never()).softDeleteExpiredBatch(any(), any(), anyInt());
    verify(jobRepository, never()).expireDoneBatch(any(), any(), anyInt());
    verify(fileAssetRepository, never()).findStaleMultipartUploads(any(), anyInt());
  }

  @Test
  void should_abortAndSoftDeleteStaleMultipartUpload_when_lockAcquired() {
    when(advisoryLockService.tryLock(anyLong())).thenReturn(true);
    when(fileCategoryRepository.findAllByRetentionDaysIsNotNull()).thenReturn(List.of());
    UUID assetId = UUID.randomUUID();
    FileAsset stale = mock(FileAsset.class);
    when(stale.getId()).thenReturn(assetId);
    when(stale.getBucketName()).thenReturn("mp-bucket");
    when(stale.getObjectKey()).thenReturn("object-key");
    when(stale.getMultipartUploadId()).thenReturn("upload-abc");
    when(fileAssetRepository.findStaleMultipartUploads(any(Instant.class), eq(500)))
        .thenReturn(List.of(stale));

    cleanupService.runCleanup();

    verify(multipartStorageService, times(1))
        .abortMultipartUpload("mp-bucket", "object-key", "upload-abc");
    verify(fileAssetRepository, times(1)).softDelete(eq(assetId), any(Instant.class));
    verify(fileAssetRepository, times(1)).clearMultipartUploadId(eq(assetId), any(Instant.class));
  }

  @Test
  void should_stillSoftDelete_when_storageAbortThrows() {
    when(advisoryLockService.tryLock(anyLong())).thenReturn(true);
    when(fileCategoryRepository.findAllByRetentionDaysIsNotNull()).thenReturn(List.of());
    UUID assetId = UUID.randomUUID();
    FileAsset stale = mock(FileAsset.class);
    when(stale.getId()).thenReturn(assetId);
    when(stale.getBucketName()).thenReturn("mp-bucket");
    when(stale.getObjectKey()).thenReturn("object-key");
    when(stale.getMultipartUploadId()).thenReturn("upload-abc");
    when(fileAssetRepository.findStaleMultipartUploads(any(Instant.class), eq(500)))
        .thenReturn(List.of(stale));
    org.mockito.Mockito.doThrow(new RuntimeException("gone"))
        .when(multipartStorageService)
        .abortMultipartUpload(any(), any(), any());

    cleanupService.runCleanup();

    verify(fileAssetRepository, times(1)).softDelete(eq(assetId), any(Instant.class));
    verify(fileAssetRepository, times(1)).clearMultipartUploadId(eq(assetId), any(Instant.class));
  }

  @Test
  void should_softDeleteExpiredFileAssetsForEachCategoryWithRetention_when_lockAcquired() {
    when(advisoryLockService.tryLock(anyLong())).thenReturn(true);
    FileCategory categoryA =
        new FileCategory(
            "AVATAR", "avatar-bucket", List.of("image/png"), 1024, false, false, 7, null);
    FileCategory categoryB =
        new FileCategory(
            "EXPORT_RESULT",
            "export-results",
            List.of("text/csv"),
            52428800,
            false,
            false,
            14,
            null);
    when(fileCategoryRepository.findAllByRetentionDaysIsNotNull())
        .thenReturn(List.of(categoryA, categoryB));

    cleanupService.runCleanup();

    verify(fileAssetRepository, times(1))
        .softDeleteExpiredBatch(eq("AVATAR"), any(Instant.class), eq(500));
    verify(fileAssetRepository, times(1))
        .softDeleteExpiredBatch(eq("EXPORT_RESULT"), any(Instant.class), eq(500));
  }

  @Test
  void should_expireDoneJobsWithThresholdFromJobDoneRetention_when_lockAcquired() {
    when(advisoryLockService.tryLock(anyLong())).thenReturn(true);
    when(fileCategoryRepository.findAllByRetentionDaysIsNotNull()).thenReturn(List.of());
    Instant before = Instant.now().minus(properties.jobDoneRetention());

    cleanupService.runCleanup();

    Instant after = Instant.now().minus(properties.jobDoneRetention());
    ArgumentCaptor<Instant> thresholdCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(jobRepository, times(1))
        .expireDoneBatch(thresholdCaptor.capture(), any(Instant.class), eq(500));
    Instant captured = thresholdCaptor.getValue();
    assertThat(captured).isBetween(before, after);
  }
}
