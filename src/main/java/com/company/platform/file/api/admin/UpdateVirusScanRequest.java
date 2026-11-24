package com.company.platform.file.api.admin;

import jakarta.validation.constraints.NotNull;

public record UpdateVirusScanRequest(@NotNull Boolean enabled, @NotNull Long version) {}
