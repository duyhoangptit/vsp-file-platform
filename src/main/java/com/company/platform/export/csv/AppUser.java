package com.company.platform.export.csv;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * Read-only: nguồn dữ liệu demo/test cho ExportUserCsvProcessor (Phase 4), không có bảng "users"
 * thật trong repo này (design doc mục 7 coi users.id thuộc DB service khác). Module export không
 * ghi vào bảng này nên không cần constructor công khai/setter.
 */
@Entity
@Table(name = "app_users")
@Getter
public class AppUser {

  @Id private UUID id;

  @Column(name = "email", nullable = false)
  private String email;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Column(name = "phone_number")
  private String phoneNumber;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "role", nullable = false)
  private String role;

  @Column(name = "country")
  private String country;

  @Column(name = "city")
  private String city;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AppUser() {}

  // Package-private, dùng trong test cùng package export.csv — module này không ghi vào bảng
  // app_users qua JPA nên không cần constructor/setter công khai cho code sản xuất.
  AppUser(
      UUID id,
      String email,
      String fullName,
      String phoneNumber,
      String status,
      String role,
      String country,
      String city,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.email = email;
    this.fullName = fullName;
    this.phoneNumber = phoneNumber;
    this.status = status;
    this.role = role;
    this.country = country;
    this.city = city;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
