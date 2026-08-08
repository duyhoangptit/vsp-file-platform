package com.company.platform.template.domain;

public class FileTemplateNotFoundException extends RuntimeException {

  public FileTemplateNotFoundException(String category, String templateCode) {
    super(
        "Không tìm thấy file template: category=%s, templateCode=%s"
            .formatted(category, templateCode));
  }

  public FileTemplateNotFoundException(String category, String templateCode, int version) {
    super(
        "Không tìm thấy file template: category=%s, templateCode=%s, version=%d"
            .formatted(category, templateCode, version));
  }
}
