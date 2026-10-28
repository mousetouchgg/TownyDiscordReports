package jp.tdr;

import java.time.*;

/** One report per local calendar day, including across daylight saving changes. */
public record DailySchedule(LocalTime time, ZoneId zone) {
  public ZonedDateTime due(Instant now) {
    var date = now.atZone(zone).toLocalDate();
    var result = date.atTime(time).atZone(zone);
    return result.toInstant().isAfter(now) ? date.minusDays(1).atTime(time).atZone(zone) : result;
  }

  public ZonedDateTime next(Instant now) {
    return due(now).toLocalDate().plusDays(1).atTime(time).atZone(zone);
  }

  // The namespace preserves old weekly snapshots even on the same calendar date.
  public static String key(LocalDate date) {
    return "daily:" + date;
  }
}
