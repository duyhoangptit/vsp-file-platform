package com.company.platform.export;

import com.company.platform.file.application.FileUploadService;
import com.company.platform.file.application.RegisterGeneratedFileCommand;
import com.company.platform.file.domain.FileAsset;
import org.springframework.stereotype.Component;

/**
 * Upload file local đã ghi xong lên MinIO + đăng ký file_assets — không có logic riêng theo format
 * nên đặt ở package export dùng chung, format export mới (Excel...) tái dùng bean này thay vì phải
 * viết lại.
 */
@Component
public class MinioResultUploader implements ResultUploader {

  private final FileUploadService fileUploadService;

  public MinioResultUploader(FileUploadService fileUploadService) {
    this.fileUploadService = fileUploadService;
  }

  @Override
  public FileAsset upload(UploadResultCommand command) {
    return fileUploadService.registerServerGeneratedFile(
        new RegisterGeneratedFileCommand(
            command.categoryCode(),
            command.localFile(),
            command.mimeType(),
            command.originalFilename(),
            command.checksumSha256(),
            command.ownerType(),
            command.ownerId(),
            command.uploadedBy(),
            command.jobId()));
  }
}
