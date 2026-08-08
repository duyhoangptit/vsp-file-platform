package com.company.platform.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.platform.file.domain.FileAsset;
import com.company.platform.file.domain.FileAssetNotFoundException;
import com.company.platform.file.domain.FileAssetRepository;
import com.company.platform.file.domain.FileNotAvailableForDownloadException;
import com.company.platform.file.domain.StorageStatus;
import com.company.platform.file.storage.MinioStorageService;
import com.company.platform.shared.audit.AuditLogService;
import com.company.platform.shared.audit.AuditResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileDownloadServiceTest {

  private final FileAssetRepository fileAssetRepository = mock(FileAssetRepository.class);
  private final MinioStorageService minioStorageService = mock(MinioStorageService.class);
  private final AuditLogService auditLogService = mock(AuditLogService.class);

  private final FileDownloadService service =
      new FileDownloadService(fileAssetRepository, minioStorageService, auditLogService);

  @Test
  void should_throwNotFound_when_prepareBytesDownload_and_fileAssetMissing() {
    UUID fileAssetId = UUID.randomUUID();
    when(fileAssetRepository.findById(fileAssetId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.prepareBytesDownload(fileAssetId))
        .isInstanceOf(FileAssetNotFoundException.class);
  }

  @Test
  void should_throwNotAvailable_when_prepareBytesDownload_and_storageStatusNotClean() {
    UUID fileAssetId = UUID.randomUUID();
    FileAsset fileAsset = mock(FileAsset.class);
    when(fileAsset.getStorageStatus()).thenReturn(StorageStatus.UPLOADED_DMZ);
    when(fileAssetRepository.findById(fileAssetId)).thenReturn(Optional.of(fileAsset));

    assertThatThrownBy(() -> service.prepareBytesDownload(fileAssetId))
        .isInstanceOf(FileNotAvailableForDownloadException.class);

    verify(minioStorageService, never()).getObject(any(), any());
    verify(auditLogService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void should_returnBytesAndRecordAudit_when_prepareBytesDownload_and_storageStatusClean() {
    UUID fileAssetId = UUID.randomUUID();
    InputStream content = new ByteArrayInputStream("hello".getBytes());
    FileAsset fileAsset = mock(FileAsset.class);
    when(fileAsset.getStorageStatus()).thenReturn(StorageStatus.CLEAN);
    when(fileAsset.getBucketName()).thenReturn("bucket");
    when(fileAsset.getObjectKey()).thenReturn("object-key");
    when(fileAsset.getOriginalFilename()).thenReturn("report.csv");
    when(fileAsset.getMimeType()).thenReturn("text/csv");
    when(fileAsset.getSizeBytes()).thenReturn(5L);
    when(fileAssetRepository.findById(fileAssetId)).thenReturn(Optional.of(fileAsset));
    when(minioStorageService.getObject("bucket", "object-key")).thenReturn(content);

    FileBytesResult result = service.prepareBytesDownload(fileAssetId);

    assertThat(result.content()).isEqualTo(content);
    assertThat(result.originalFilename()).isEqualTo("report.csv");
    assertThat(result.mimeType()).isEqualTo("text/csv");
    assertThat(result.sizeBytes()).isEqualTo(5L);
    verify(auditLogService)
        .record(
            isNull(),
            eq("FILE_DOWNLOADED_INTERNAL"),
            eq("FILE_ASSET"),
            eq(fileAssetId),
            eq(AuditResult.SUCCESS));
  }

  @Test
  void should_throwNotFound_when_generateDownloadUrl_and_fileAssetMissing() {
    UUID fileAssetId = UUID.randomUUID();
    when(fileAssetRepository.findById(fileAssetId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.generateDownloadUrl(fileAssetId))
        .isInstanceOf(FileAssetNotFoundException.class);
  }

  @Test
  void should_returnPresignedUrl_when_generateDownloadUrl_and_storageStatusClean() {
    UUID fileAssetId = UUID.randomUUID();
    FileAsset fileAsset = mock(FileAsset.class);
    when(fileAsset.getStorageStatus()).thenReturn(StorageStatus.CLEAN);
    when(fileAsset.getBucketName()).thenReturn("bucket");
    when(fileAsset.getObjectKey()).thenReturn("object-key");
    when(fileAssetRepository.findById(fileAssetId)).thenReturn(Optional.of(fileAsset));
    when(minioStorageService.generatePresignedGetUrl(eq("bucket"), eq("object-key"), any()))
        .thenReturn("https://minio/presigned");

    FileDownloadResult result = service.generateDownloadUrl(fileAssetId);

    assertThat(result.downloadUrl()).isEqualTo("https://minio/presigned");
  }
}
