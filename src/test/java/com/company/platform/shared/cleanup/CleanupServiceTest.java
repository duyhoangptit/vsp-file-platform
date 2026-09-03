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

import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileCategory;
import com.company.platform.file.domain.FileCategoryRepository;
import com.company.platform.job.domain.JobRepository;
import com.company.platform.shared.lock.AdvisoryLockService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CleanupServiceTest {

  private final FileCategoryRepository fileCategoryRepository = mock(FileCategoryRepository.class);
  private final FileAssetRepository fileAssetRepository = mock(FileAssetRepository.class);
  private final JobRepository jobRepository = mock(JobRepository.class);
  private final AdvisoryLockService advisoryLockService = mock(AdvisoryLockService.class);
  private final CleanupProperties properties =
      new CleanupProperties(Duration.ofMinutes(15), 500, Duration.ofDays(30));
  private final CleanupService cleanupService =
      new CleanupService(
          fileCategoryRepository,
          fileAssetRepository,
          jobRepository,
          advisoryLockService,
          properties);

  @Test
  void should_doNothing_when_lockNotAcquired() {
    when(advisoryLockService.tryLock(anyLong())).thenReturn(false);

    cleanupService.runCleanup();

    verify(fileCategoryRepository, never()).findAllByRetentionDaysIsNotNull();
    verify(fileAssetRepository, never()).softDeleteExpiredBatch(any(), any(), anyInt());
    verify(jobRepository, never()).expireDoneBatch(any(), any(), anyInt());
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
