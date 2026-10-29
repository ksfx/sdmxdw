package de.sdmxdw.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Parses "as of" vintage parameters of the explorer and the REST API. */
public final class AsOf {

  private AsOf() {}

  /**
   * Accepts a date ("2026-10-28" = state at the end of that day, UTC), a date-time without zone
   * ("2026-10-28T15:58", UTC) or an ISO instant ("2026-10-28T15:58:00Z"); blank = null (current).
   */
  public static Instant parse(String asOf) {
    if (asOf == null || asOf.isBlank()) {
      return null;
    }
    String value = asOf.trim();
    try {
      if (value.length() == 10) {
        return LocalDate.parse(value)
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .minusNanos(1000);
      }
      if (value.length() == 16 || value.length() == 19) {
        return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
      }
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "Invalid asOf '" + asOf + "': use yyyy-MM-dd, yyyy-MM-ddTHH:mm or an ISO instant");
    }
  }
}
