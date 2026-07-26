package com.company.platform.import_.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * Mapping riêng cho module import (ghi), tách khỏi export.csv.AppUser (chỉ đọc, package-private
 * constructor) để 2 module export/import không phụ thuộc lẫn nhau qua 1 entity dùng chung — cùng
 * ánh xạ vào bảng app_users nhưng export chỉ đọc, import chỉ insert dòng mới, không bao giờ cùng
 * đụng 1 row trong 1 transaction.
 */
@Entity
@Table(name = "app_users")
@Getter
public class AppUserRecord {

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

  protected AppUserRecord() {}

  public AppUserRecord(
      UUID id,
      String email,
      String fullName,
      String phoneNumber,
      String status,
      String role,
      String country,
      String city) {
    Instant now = Instant.now();
    this.id = id;
    this.email = email;
    this.fullName = fullName;
    this.phoneNumber = phoneNumber;
    this.status = status;
    this.role = role;
    this.country = country;
    this.city = city;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
