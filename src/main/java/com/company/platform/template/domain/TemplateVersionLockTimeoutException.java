package com.company.platform.template.domain;

public class TemplateVersionLockTimeoutException extends RuntimeException {

  public TemplateVersionLockTimeoutException(String category, String templateCode) {
    super(
        "Không lấy được khoá version cho category=%s, templateCode=%s sau nhiều lần thử, thử lại"
            .formatted(category, templateCode));
  }
}
