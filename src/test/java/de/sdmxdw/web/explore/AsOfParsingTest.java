package de.sdmxdw.web.explore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AsOfParsingTest {

  @Test
  void dateMeansEndOfThatDayInUtc() {
    Instant asOf = ExploreController.parseAsOf("2026-10-27");
    assertThat(asOf).isBefore(Instant.parse("2026-10-28T00:00:00Z"));
    assertThat(asOf).isAfter(Instant.parse("2026-10-27T23:59:59Z"));
  }

  @Test
  void isoInstantIsUsedAsIsAndBlankMeansCurrent() {
    assertThat(ExploreController.parseAsOf("2026-10-27T06:00:00Z"))
        .isEqualTo(Instant.parse("2026-10-27T06:00:00Z"));
    assertThat(ExploreController.parseAsOf("2026-10-27T12:30"))
        .isEqualTo(Instant.parse("2026-10-27T12:30:00Z"));
    assertThat(ExploreController.parseAsOf(" ")).isNull();
    assertThat(ExploreController.parseAsOf(null)).isNull();
  }
}
