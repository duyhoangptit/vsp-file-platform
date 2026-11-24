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
          .withUserConfiguration(SecurityConfig.class)
          .withPropertyValues(
              "app.admin-security.enabled=false",
              "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks");

  private static Throwable root(Throwable t) {
    while (t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  @Test
  void should_failStartup_when_securityDisabledInProd() {
    runner
        .withPropertyValues("spring.profiles.active=prod")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(root(context.getStartupFailure()))
                  .hasMessageContaining("only allowed with profile local or dev");
            });
  }

  @Test
  void should_failStartup_when_securityDisabledWithoutLocalProfile() {
    runner.run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("spring.profiles.active=staging")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void should_start_when_securityDisabledInDevProfile() {
    runner
        .withPropertyValues("spring.profiles.active=dev")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
