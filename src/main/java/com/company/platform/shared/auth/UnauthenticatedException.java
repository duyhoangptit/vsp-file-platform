package com.company.platform.shared.auth;

public class UnauthenticatedException extends RuntimeException {

  public UnauthenticatedException(String message) {
    super(message);
  }
}
