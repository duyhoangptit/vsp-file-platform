package com.company.platform.file.domain;

public class InvalidPartNumberException extends RuntimeException {

  public InvalidPartNumberException(int partNumber, int maxParts) {
    super("Part number %d không hợp lệ (phải trong 1..%d)".formatted(partNumber, maxParts));
  }
}
