package de.sdmxdw.process;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.scheduling.support.CronExpression;

/** Renders the next few run times of a cron schedule for display in the ingest job UIs. */
public final class CronDescriptions {

  private static final DateTimeFormatter NEXT_RUN_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private CronDescriptions() {}

  public static String describeNextRuns(String cron) {
    try {
      CronExpression expression = CronExpression.parse(cron);
      LocalDateTime next = LocalDateTime.now();
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < 3; i++) {
        next = expression.next(next);
        if (next == null) {
          break;
        }
        if (!result.isEmpty()) {
          result.append(", ");
        }
        result.append(next.format(NEXT_RUN_FORMAT));
      }
      return result.toString();
    } catch (IllegalArgumentException e) {
      return "(invalid cron expression)";
    }
  }
}
