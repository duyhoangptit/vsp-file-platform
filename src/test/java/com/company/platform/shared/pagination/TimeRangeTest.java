package com.company.platform.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeRangeTest {

  private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void should_defaultToLast30Days_when_bothMissing() {
    TimeRange range = TimeRange.resolve(null, null, clock);
    assertThat(range.from()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
    assertThat(range.to()).isEqualTo(NOW);
  }

  @Test
  void should_accept_when_exactly92Days() {
    Instant from = NOW.minus(Duration.ofDays(92));
    assertThat(TimeRange.resolve(from, NOW, clock).from()).isEqualTo(from);
  }

  @Test
  void should_reject_when_longerThan92Days() {
    Instant from = NOW.minus(Duration.ofDays(92)).minusSeconds(1);
    assertThatThrownBy(() -> TimeRange.resolve(from, NOW, clock))
        .isInstanceOf(InvalidTimeRangeException.class);
  }

  @Test
  void should_reject_when_fromNotBeforeTo() {
    assertThatThrownBy(() -> TimeRange.resolve(NOW, NOW, clock))
        .isInstanceOf(InvalidTimeRangeException.class);
  }

  @Test
  void should_clampLimit_when_outOfBounds() {
    assertThat(PageSlice.clampLimit(null)).isEqualTo(50);
    assertThat(PageSlice.clampLimit(0)).isEqualTo(1);
    assertThat(PageSlice.clampLimit(500)).isEqualTo(200);
    assertThat(PageSlice.clampLimit(75)).isEqualTo(75);
  }
}
