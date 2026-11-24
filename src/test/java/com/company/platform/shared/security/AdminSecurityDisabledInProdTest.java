package com.company.platform.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class AdminSecurityDisabledInProdTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
          .withUserConfiguration(SecurityConfig.class);

  @Test
  void should_failStartup_when_securityDisabledInProd() {
    runner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "app.admin-security.enabled=false",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable root = context.getStartupFailure();
              while (root.getCause() != null) {
                root = root.getCause();
              }
              assertThat(root).hasMessageContaining("not allowed with profile 'prod'");
            });
  }
}
