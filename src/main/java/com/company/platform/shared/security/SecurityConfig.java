package com.company.platform.shared.security;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 2 filter chain:
 *
 * <ol>
 *   <li>{@code /api/admin/**} — stateless OAuth2 resource server cho JWT do vsp-identity-service
 *       cấp (ES256, JWKS). Authorities = từng phần tử claim {@code perms} (VD {@code
 *       file.asset.view}, xem {@link FilePermissions}). Phân quyền theo path + method.
 *   <li>Mọi path còn lại — {@code permitAll}: giữ nguyên cơ chế hiện có ({@code UserContext} từ
 *       header, {@code @RequiresAccess}, {@code InternalApiKeyInterceptor}), không bị JWT chặn.
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({
  AdminSecurityProperties.class,
  OAuth2ResourceServerProperties.class
})
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  static final String ADMIN_PATTERN = "/api/admin/**";
  static final String LOCAL_PRINCIPAL = "local-dev";
  static final java.util.Set<String> LOCAL_PROFILES = java.util.Set.of("local", "dev");

  @Bean
  @Order(1)
  SecurityFilterChain adminSecurityFilterChain(
      HttpSecurity http, AdminSecurityProperties props, Environment env) throws Exception {
    http.securityMatcher(ADMIN_PATTERN)
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .logout(l -> l.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> writeError(res, 401, "Thiếu hoặc sai access token"))
                    .accessDeniedHandler(
                        (req, res, ex) -> writeError(res, 403, "Không có quyền truy cập")));

    if (!props.enabled()) {
      // Allow-list (không phải deny "prod"): profile khác tên hoặc không có profile vẫn fail.
      if (Arrays.stream(env.getActiveProfiles()).noneMatch(LOCAL_PROFILES::contains)) {
        throw new IllegalStateException(
            "app.admin-security.enabled=false is only allowed with profile local or dev");
      }
      log.warn(
          "ADMIN SECURITY DISABLED (app.admin-security.enabled=false): every admin caller has"
              + " every file.* permission. Local use only.");
      List<GrantedAuthority> everything =
          FilePermissions.ALL.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
      http.anonymous(a -> a.principal(LOCAL_PRINCIPAL).authorities(everything))
          .authorizeHttpRequests(a -> a.anyRequest().permitAll());
      return http.build();
    }

    http.authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.GET, "/api/admin/files/*/history")
                    .hasAuthority(FilePermissions.HISTORY_VIEW)
                    .requestMatchers(HttpMethod.GET, "/api/admin/files", "/api/admin/files/**")
                    .hasAuthority(FilePermissions.ASSET_VIEW)
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/admin/file-downloads",
                        "/api/admin/file-downloads/**",
                        "/api/admin/config-changes",
                        "/api/admin/config-changes/**")
                    .hasAuthority(FilePermissions.HISTORY_VIEW)
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/admin/virus-scan",
                        "/api/admin/file-categories",
                        "/api/admin/file-categories/**")
                    .hasAuthority(FilePermissions.CONFIG_VIEW)
                    .requestMatchers(HttpMethod.PUT, "/api/admin/virus-scan")
                    .hasAuthority(FilePermissions.CONFIG_MANAGE)
                    .requestMatchers(HttpMethod.PATCH, "/api/admin/file-categories/*")
                    .hasAuthority(FilePermissions.CONFIG_MANAGE)
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .logout(l -> l.disable())
        .requestCache(r -> r.disable())
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(
      OAuth2ResourceServerProperties resourceServer, AdminSecurityProperties props) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(resourceServer.getJwt().getJwkSetUri())
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .build();
    decoder.setJwtValidator(
        props.issuer().isBlank()
            ? JwtValidators.createDefault()
            : JwtValidators.createDefaultWithIssuer(props.issuer()));
    return decoder;
  }

  /** Chặn Spring Boot tự sinh user + password mặc định (app không dùng form/basic login). */
  @Bean
  InMemoryUserDetailsManager noLocalUsers() {
    return new InMemoryUserDetailsManager();
  }

  static JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new PermsAuthoritiesConverter());
    return converter;
  }

  /** Authorities = claim {@code perms} as-is; {@code sub} bắt buộc là UUID (actor của audit). */
  static final class PermsAuthoritiesConverter
      implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
      requireUuidSubject(jwt);
      List<GrantedAuthority> out = new ArrayList<>();
      List<String> perms = jwt.getClaimAsStringList("perms");
      if (perms != null) {
        perms.forEach(p -> out.add(new SimpleGrantedAuthority(p)));
      }
      return out;
    }

    private static void requireUuidSubject(Jwt jwt) {
      String subject = jwt.getSubject();
      try {
        UUID.fromString(subject == null ? "" : subject);
      } catch (IllegalArgumentException e) {
        // AuthenticationException → entry point 401 (lỗi khác từ converter sẽ thành 500)
        throw new InvalidBearerTokenException("JWT sub phải là UUID");
      }
    }
  }

  private static void writeError(
      jakarta.servlet.http.HttpServletResponse res, int status, String message) throws IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.getWriter()
        .write(
            "{\"status\":%d,\"message\":\"%s\",\"timestamp\":\"%s\"}"
                .formatted(status, message, Instant.now()));
  }
}
