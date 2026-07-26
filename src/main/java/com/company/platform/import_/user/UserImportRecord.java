package com.company.platform.import_.user;

public record UserImportRecord(
    String email,
    String fullName,
    String phoneNumber,
    String status,
    String role,
    String country,
    String city) {}
