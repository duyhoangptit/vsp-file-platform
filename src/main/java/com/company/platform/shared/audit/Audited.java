package com.company.platform.shared.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đặt trên method thực hiện 1 state transition (CAS update) để tự động ghi audit_logs sau khi
 * method chạy thành công, thay vì gọi AuditLogService.record(...) thủ công trong business logic.
 *
 * <p>Method được annotate PHẢI có UUID làm tham số đầu tiên (dùng làm resource_id). "Thành công"
 * nghĩa là: method void -> không throw exception; method trả về boolean -> không throw VÀ giá trị
 * trả về là true (false = transition không xảy ra, VD thua race CAS -> không log).
 *
 * <p>actorId luôn ghi null: method dùng annotation này chạy từ worker/background thread
 * (AbstractJobProcessor), không gắn với UserContext của 1 actor cụ thể.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

  String action();

  String resourceType();

  AuditResult result();
}
