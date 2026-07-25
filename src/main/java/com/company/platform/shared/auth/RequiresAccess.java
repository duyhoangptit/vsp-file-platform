package com.company.platform.shared.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đặt trên Controller method để bắt buộc check quyền qua ResourceAuthorizationService trước khi
 * method chạy — resourceId lấy từ tham số @PathVariable đầu tiên của method. Enforce qua
 * ResourceAuthorizationAspect, không rải if-else trong Controller.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAccess {

  ResourceType type();

  Action action();
}
