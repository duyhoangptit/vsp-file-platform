package com.company.platform.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = AdminSecurityTest.PingController.class)
@Import({SecurityConfig.class, AdminActor.class, AdminSecurityTest.PingController.class})
@TestPropertySource(
    properties = {
      "app.admin-security.enabled=true",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks"
    })
class AdminSecurityTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtDecoder jwtDecoder;

  @RestController
  static class PingController {
    private final AdminActor adminActor;

    PingController(AdminActor adminActor) {
      this.adminActor = adminActor;
    }

    @GetMapping("/api/admin/files")
    String files() {
      return String.valueOf(adminActor.currentId());
    }

    @GetMapping("/api/admin/files/{id}/history")
    String history(@PathVariable String id) {
      return "ok";
    }

    @PutMapping("/api/admin/virus-scan")
    String virusScan() {
      return "ok";
    }

    @GetMapping("/api/admin/unknown")
    String unknown() {
      return "ok";
    }

    @GetMapping("/api/files/ping")
    String publicPing() {
      return "ok";
    }

    @PostMapping("/api/files/ping")
    String publicPost() {
      return "ok";
    }
  }

  private static SimpleGrantedAuthority perm(String p) {
    return new SimpleGrantedAuthority(p);
  }

  @Test
  void should_return401_when_adminRequestHasNoToken() throws Exception {
    mockMvc
        .perform(get("/api/admin/files"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void should_return403_when_tokenLacksPermission() throws Exception {
    mockMvc
        .perform(get("/api/admin/files").with(jwt().authorities(perm(FilePermissions.CONFIG_VIEW))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  void should_return200_when_tokenHasAssetView() throws Exception {
    UUID sub = UUID.randomUUID();
    mockMvc
        .perform(
            get("/api/admin/files")
                .with(
                    jwt()
                        .jwt(j -> j.subject(sub.toString()))
                        .authorities(perm(FilePermissions.ASSET_VIEW))))
        .andExpect(status().isOk())
        .andExpect(content().string(sub.toString()));
  }

  @Test
  void should_requireHistoryView_when_readingFileHistory() throws Exception {
    String path = "/api/admin/files/" + UUID.randomUUID() + "/history";
    mockMvc
        .perform(get(path).with(jwt().authorities(perm(FilePermissions.ASSET_VIEW))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get(path).with(jwt().authorities(perm(FilePermissions.HISTORY_VIEW))))
        .andExpect(status().isOk());
  }

  @Test
  void should_requireConfigManage_when_updatingVirusScan() throws Exception {
    mockMvc
        .perform(
            put("/api/admin/virus-scan").with(jwt().authorities(perm(FilePermissions.CONFIG_VIEW))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/admin/virus-scan")
                .with(jwt().authorities(perm(FilePermissions.CONFIG_MANAGE))))
        .andExpect(status().isOk());
  }

  @Test
  void should_denyAll_when_adminPathIsNotMapped() throws Exception {
    SimpleGrantedAuthority[] all =
        FilePermissions.ALL.stream()
            .map(SimpleGrantedAuthority::new)
            .toArray(SimpleGrantedAuthority[]::new);
    mockMvc
        .perform(get("/api/admin/unknown").with(jwt().authorities(all)))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_passThroughWithoutToken_when_pathIsNotAdmin() throws Exception {
    mockMvc.perform(get("/api/files/ping")).andExpect(status().isOk());
    mockMvc.perform(post("/api/files/ping")).andExpect(status().isOk());
  }

  @Test
  void should_return401_when_subjectIsNotUuid() throws Exception {
    Jwt token =
        new Jwt(
            "x",
            Instant.now(),
            Instant.now().plusSeconds(300),
            Map.of("alg", "ES256"),
            Map.of("sub", "abc", "perms", List.of(FilePermissions.ASSET_VIEW)));
    when(jwtDecoder.decode(anyString())).thenReturn(token);
    mockMvc
        .perform(get("/api/admin/files").header("Authorization", "Bearer x"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_grantPermsClaim_when_realTokenDecoded() throws Exception {
    UUID sub = UUID.randomUUID();
    Jwt token =
        new Jwt(
            "x",
            Instant.now(),
            Instant.now().plusSeconds(300),
            Map.of("alg", "ES256"),
            Map.of("sub", sub.toString(), "perms", List.of(FilePermissions.ASSET_VIEW)));
    when(jwtDecoder.decode(anyString())).thenReturn(token);
    String body =
        mockMvc
            .perform(get("/api/admin/files").header("Authorization", "Bearer x"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).isEqualTo(sub.toString());
  }
}
