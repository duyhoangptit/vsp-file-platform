package com.company.platform.shared.security;

import java.util.List;

/**
 * Permission code của identity application {@code file} (seed {@code
 * V202611241100__identity_seed_file_app.sql} bên vsp-identity-service), đọc từ claim {@code perms}
 * của JWT. Chỉ áp cho {@code /api/admin/**}.
 */
public final class FilePermissions {

  public static final String ASSET_VIEW = "file.asset.view";
  public static final String HISTORY_VIEW = "file.history.view";
  public static final String CONFIG_VIEW = "file.config.view";
  public static final String CONFIG_MANAGE = "file.config.manage";

  public static final List<String> ALL =
      List.of(ASSET_VIEW, HISTORY_VIEW, CONFIG_VIEW, CONFIG_MANAGE);

  private FilePermissions() {}
}
