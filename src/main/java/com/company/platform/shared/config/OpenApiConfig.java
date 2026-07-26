package com.company.platform.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Job & File Management Platform API")
                .version("0.0.1-SNAPSHOT")
                .description(
                    "REST API cho upload/download file và xử lý job nền (export/import)."));
  }
}
