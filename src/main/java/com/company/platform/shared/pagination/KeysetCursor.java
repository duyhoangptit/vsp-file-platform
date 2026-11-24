package com.company.platform.shared.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Vị trí keyset {@code (created_at, id)} của dòng cuối trang trước, encode opaque (base64url của
 * {@code createdAt|id}) để client chỉ cần truyền lại nguyên văn.
 */
public record KeysetCursor(Instant createdAt, UUID id) {

  public String encode() {
    String raw = createdAt + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static KeysetCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int sep = raw.indexOf('|');
      if (sep < 0) {
        throw new InvalidCursorException();
      }
      Instant createdAt = Instant.parse(raw.substring(0, sep));
      if (!StorableInstants.isStorable(createdAt)) {
        throw new InvalidCursorException();
      }
      return new KeysetCursor(createdAt, UUID.fromString(raw.substring(sep + 1)));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new InvalidCursorException();
    }
  }

  public static KeysetCursor decodeOrNull(String cursor) {
    return cursor == null || cursor.isBlank() ? null : decode(cursor);
  }
}
