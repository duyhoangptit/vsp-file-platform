package com.company.platform.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserContextConfigTest {

  private final UserContextConfig config = new UserContextConfig();

  @Test
  void should_returnUserContext_when_headersArePresentAndValid() {
    UUID userId = UUID.randomUUID();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-User-Id")).thenReturn(userId.toString());
    when(request.getHeader("X-User-Roles")).thenReturn("ADMIN, USER");

    UserContext userContext = config.userContext(request);

    assertThat(userContext.userId()).isEqualTo(userId);
    assertThat(userContext.roles()).containsExactlyInAnyOrder("ADMIN", "USER");
  }

  @Test
  void should_returnEmptyRoles_when_rolesHeaderIsAbsent() {
    UUID userId = UUID.randomUUID();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-User-Id")).thenReturn(userId.toString());
    when(request.getHeader("X-User-Roles")).thenReturn(null);

    UserContext userContext = config.userContext(request);

    assertThat(userContext.roles()).isEmpty();
  }

  @Test
  void should_throwUnauthenticatedException_when_userIdHeaderIsMissing() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-User-Id")).thenReturn(null);

    assertThatThrownBy(() -> config.userContext(request))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void should_throwUnauthenticatedException_when_userIdHeaderIsNotAValidUuid() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-User-Id")).thenReturn("not-a-uuid");

    assertThatThrownBy(() -> config.userContext(request))
        .isInstanceOf(UnauthenticatedException.class);
  }
}
