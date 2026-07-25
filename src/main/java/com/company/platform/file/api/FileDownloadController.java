package com.company.platform.file.api;

import com.company.platform.file.application.FileDownloadService;
import com.company.platform.shared.auth.Action;
import com.company.platform.shared.auth.RequiresAccess;
import com.company.platform.shared.auth.ResourceType;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files")
public class FileDownloadController {

  private final FileDownloadService fileDownloadService;

  public FileDownloadController(FileDownloadService fileDownloadService) {
    this.fileDownloadService = fileDownloadService;
  }

  @GetMapping("/{fileAssetId}/download-url")
  @RequiresAccess(type = ResourceType.FILE_ASSET, action = Action.READ)
  public FileDownloadResponse getDownloadUrl(@PathVariable UUID fileAssetId) {
    return FileDownloadResponse.from(fileDownloadService.generateDownloadUrl(fileAssetId));
  }
}
