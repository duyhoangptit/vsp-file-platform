package com.company.platform.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class InternalApiKeyInterceptorTest {

  private static final String HEADER = InternalApiKeyInterceptor.INTERNAL_API_KEY_HEADER;

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final HttpServletResponse response = mock(HttpServletResponse.class);

  @Test
  void should_returnTrue_when_headerMatchesConfiguredKey() {
    InternalApiKeyInterceptor interceptor =
        new InternalApiKeyInterceptor(new InternalApiKeyProperties("correct-key"));
    when(request.getHeader(HEADER)).thenReturn("correct-key");

    boolean allowed = interceptor.preHandle(request, response, new Object());

    assertThat(allowed).isTrue();
  }

  @Test
  void should_throwUnauthenticated_when_headerMissing() {
    InternalApiKeyInterceptor interceptor =
        new InternalApiKeyInterceptor(new InternalApiKeyProperties("correct-key"));
    when(request.getHeader(HEADER)).thenReturn(null);

    assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void should_throwUnauthenticated_when_headerDoesNotMatchConfiguredKey() {
    InternalApiKeyInterceptor interceptor =
        new InternalApiKeyInterceptor(new InternalApiKeyProperties("correct-key"));
    when(request.getHeader(HEADER)).thenReturn("wrong-key");

    assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void should_throwUnauthenticated_when_configuredKeyIsBlank_evenIfHeaderIsBlankToo() {
    InternalApiKeyInterceptor interceptor =
        new InternalApiKeyInterceptor(new InternalApiKeyProperties(""));
    when(request.getHeader(HEADER)).thenReturn("");

    assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void should_throwUnauthenticated_when_configuredKeyIsNull() {
    InternalApiKeyInterceptor interceptor =
        new InternalApiKeyInterceptor(new InternalApiKeyProperties(null));
    when(request.getHeader(HEADER)).thenReturn("anything");

    assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
        .isInstanceOf(UnauthenticatedException.class);
  }
}
