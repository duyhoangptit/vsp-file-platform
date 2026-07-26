package com.company.platform.import_.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateImportUserRequest(@NotNull UUID fileAssetId, String idempotencyKey) {}
