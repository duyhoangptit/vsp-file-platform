package com.company.platform.file.api;

import com.company.platform.file.application.FileUploadInitiation;
import com.company.platform.file.application.FileUploadService;
import com.company.platform.file.application.InitiateFileUploadCommand;
import com.company.platform.file.domain.FileAsset;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.RequiresAccess;
import com.company.platform.shared.auth.ResourceType;
import com.company.platform.shared.auth.UserContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {

  private final FileUploadService fileUploadService;
  private final UserContext userContext;

  public FileUploadController(FileUploadService fileUploadService, UserContext userContext) {
    this.fileUploadService = fileUploadService;
    this.userContext = userContext;
  }

  @PostMapping
  public InitiateFileUploadResponse initiateUpload(
      @Valid @RequestBody InitiateFileUploadRequest request) {
    FileUploadInitiation initiation =
        fileUploadService.initiateUpload(
            new InitiateFileUploadCommand(
                request.categoryCode(),
                request.originalFilename(),
                request.mimeType(),
                request.sizeBytes(),
                request.ownerType(),
                userContext.userId(),
                userContext.userId()));
    return InitiateFileUploadResponse.from(initiation);
  }

  @PostMapping("/{fileAssetId}/confirm")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.WRITE)
  public FileAssetResponse confirmUpload(
      @PathVariable UUID fileAssetId, @Valid @RequestBody ConfirmFileUploadRequest request) {
    FileAsset fileAsset = fileUploadService.confirmUpload(fileAssetId, request.checksumSha256());
    return FileAssetResponse.from(fileAsset);
  }
}
