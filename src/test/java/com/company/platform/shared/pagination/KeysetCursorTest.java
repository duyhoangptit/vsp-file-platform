package com.company.platform.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeysetCursorTest {

  private static String b64(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void should_roundTrip_when_encodedThenDecoded() {
    KeysetCursor cursor =
        new KeysetCursor(Instant.parse("2026-11-24T01:02:03.123456789Z"), UUID.randomUUID());
    assertThat(KeysetCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @Test
  void should_throwInvalidCursor_when_garbage() {
    assertThatThrownBy(() -> KeysetCursor.decode("!!!")).isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> KeysetCursor.decode(b64("abc")))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> KeysetCursor.decode(b64("2026-01-01T00:00:00Z|not-a-uuid")))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> KeysetCursor.decode(b64("yesterday|" + UUID.randomUUID())))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void should_throwInvalidCursor_when_instantOutsideStorableRange() {
    assertThatThrownBy(
            () -> KeysetCursor.decode(b64("+300000-01-01T00:00:00Z|" + UUID.randomUUID())))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> KeysetCursor.decode(b64("1969-12-31T23:59:59Z|" + UUID.randomUUID())))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void should_returnNull_when_cursorBlank() {
    assertThat(KeysetCursor.decodeOrNull(null)).isNull();
    assertThat(KeysetCursor.decodeOrNull("  ")).isNull();
  }
}
