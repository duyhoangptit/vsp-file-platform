package com.company.platform.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  /** Scheme cho /api/admin/** (JWT của vsp-identity-service, claim perms = file.*). */
  public static final String ADMIN_BEARER_SCHEME = "bearer-jwt";

  @Bean
  public OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Job & File Management Platform API")
                .version("0.0.1-SNAPSHOT")
                .description("REST API cho upload/download file và xử lý job nền (export/import)."))
        .components(
            new Components()
                .addSecuritySchemes(
                    ADMIN_BEARER_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "Access token của vsp-identity-service; quyền theo claim perms"
                                + " (file.asset.view, file.history.view, file.config.view,"
                                + " file.config.manage). Chỉ áp cho /api/admin/**.")));
  }
}
