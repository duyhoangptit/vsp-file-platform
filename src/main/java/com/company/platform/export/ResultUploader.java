package com.company.platform.export;

import com.company.platform.file.domain.FileAsset;

public interface ResultUploader {

  FileAsset upload(UploadResultCommand command);
}
