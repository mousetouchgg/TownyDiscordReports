package jp.tdr;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyTest {
  @TempDir Path temp;
  private final DailySchedule schedule = new DailySchedule(LocalTime.NOON, ZoneId.of("Asia/Tokyo"));

  @Test
  void noonBoundaryUsesTokyoCalendarEveryDay() {
    assertEquals(
        LocalDate.of(2026, 10, 28),
        schedule.due(Instant.parse("2026-10-29T02:59:59Z")).toLocalDate());
    assertEquals(
        LocalDate.of(2026, 10, 29),
        schedule.due(Instant.parse("2026-10-29T03:00:00Z")).toLocalDate());
    assertEquals(
        Instant.parse("2026-10-30T03:00:00Z"),
        schedule.next(Instant.parse("2026-10-29T03:00:00Z")).toInstant());
    assertEquals(
        Instant.parse("2026-10-29T03:00:00Z"),
        schedule.next(Instant.parse("2026-10-29T02:59:59Z")).toInstant());
  }

  @Test
  void followsLocalNoonAcrossShortAndLongDstDays() {
    var s = new DailySchedule(LocalTime.NOON, ZoneId.of("America/New_York"));
    var spring = Instant.parse("2026-03-07T17:00:00Z");
    var autumn = Instant.parse("2026-10-31T16:00:00Z");
    assertEquals(12, s.next(spring).getHour());
    assertEquals(23, Duration.between(spring, s.next(spring).toInstant()).toHours());
    assertEquals(12, s.next(autumn).getHour());
    assertEquals(25, Duration.between(autumn, s.next(autumn).toInstant()).toHours());
  }

  @Test
  void upgradeStartsAtNextNoonAndPreservesCursorAcrossDowntime() throws Exception {
    var file = temp.resolve("history.db");
    try (var db = new Store(file)) {
      db.metadata("next-week", "2026-11-01");
      assertEquals(
          "2026-10-29", db.ensureDailyCursor(schedule, Instant.parse("2026-10-29T01:00:00Z")));
      assertEquals("2026-11-01", db.metadata("next-week"));
    }
    try (var db = new Store(file)) {
      assertEquals(
          "2026-10-29", db.ensureDailyCursor(schedule, Instant.parse("2026-11-02T01:00:00Z")));
    }
    try (var db = new Store(temp.resolve("after-noon.db"))) {
      assertEquals(
          "2026-10-30", db.ensureDailyCursor(schedule, Instant.parse("2026-10-29T04:00:00Z")));
    }
  }

  @Test
  void dailySnapshotsNeverOverwriteWeeklyHistoryOrReplayWeeklyPending() throws Exception {
    var day = LocalDate.of(2026, 10, 29);
    var at = Instant.parse("2026-10-29T03:00:00Z");
    var oldRows = Ranking.sort(List.of(Ranking.Value.of("a", "旧名", 10)));
    var newRows = Ranking.sort(List.of(Ranking.Value.of("a", "新名", 20)));
    try (var db = new Store(temp.resolve("history.db"))) {
      db.save(
          day.toString(), at, at, Map.of("player", oldRows), List.of("old-weekly"), "2026-11-05");
      assertTrue(
          db.saveDaily(
              day, at, at, Map.of("player", newRows), List.of("daily-player", "daily-nation")));
      assertFalse(db.saveDaily(day, at, at, Map.of(), List.of("duplicate")));
      assertEquals(oldRows, db.rows(day.toString(), "player"));
      assertEquals(newRows, db.rows(DailySchedule.key(day), "player"));
      assertEquals("2026-10-30", db.metadata("next-day"));
      assertEquals("2026-11-05", db.metadata("next-week"));
      assertEquals(2, db.pendingDaily().size());
      assertEquals(3, db.pending().size());
      for (var part : db.pendingDaily()) {
        assertTrue(db.claim(part));
        assertFalse(db.claim(part));
        db.state(part, "SENT");
      }
      assertTrue(db.pendingDaily().isEmpty());
      assertEquals("old-weekly", db.pending().getFirst().payload());
    }
  }

  @Test
  void comparesAgainstPreviousCalendarDayAndRetainsDailyHistory() throws Exception {
    var day = LocalDate.of(2026, 10, 29);
    var at = Instant.now();
    var file = temp.resolve("daily.db");
    var yesterday =
        Ranking.sort(List.of(Ranking.Value.of("a", "Alice", 10), Ranking.Value.of("b", "Bob", 20)));
    var today =
        Ranking.sort(List.of(Ranking.Value.of("a", "Alice", 30), Ranking.Value.of("b", "Bob", 5)));
    try (var db = new Store(file)) {
      db.saveDaily(day.minusDays(1), at, at, Map.of("player", yesterday), List.of("yesterday"));
      db.saveDaily(day, at, at, Map.of("player", today), List.of("today"));
    }
    try (var db = new Store(file)) {
      assertTrue(db.exists(DailySchedule.key(day.minusDays(1))));
      assertEquals(
          "前日比 +20.0 / 順位 ↑1",
          Ranking.comparison(
              today.getFirst(), db.rows(DailySchedule.key(day.minusDays(1)), "player"), true));
      assertEquals("2026-10-30", db.metadata("next-day"));
    }
  }

  @Test
  void dailyEmbedNamesDateAndMissingHistory() {
    var payload =
        Embed.ranking("日次ランキング", List.of(), List.of(), false, 10, "2026-10-29").getFirst();
    assertTrue(payload.contains("対象日：2026-10-29"));
    assertTrue(payload.contains("前日データなし"));
    assertFalse(payload.contains("前週"));
  }
}
