package jp.tdr;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {
  @TempDir Path temp;

  Ranking.Value v(String id, double balance) {
    return Ranking.Value.of(id, id, balance);
  }

  @Test
  void sortDescending() {
    var r = Ranking.sort(List.of(v("a", 1), v("b", 3), v("c", 2)));
    assertEquals(List.of("b", "c", "a"), r.stream().map(Ranking.Row::id).toList());
  }

  @Test
  void tiesUseUuidAndOrdinalRank() {
    var r = Ranking.sort(List.of(v("b", 5), v("a", 5)));
    assertEquals("a", r.getFirst().id());
    assertEquals(2, r.get(1).rank());
  }

  @Test
  void invalidBalances() {
    assertThrows(IllegalArgumentException.class, () -> v("a", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> v("a", Double.POSITIVE_INFINITY));
  }

  @Test
  void negativeBalanceSupported() {
    assertEquals("b", Ranking.sort(List.of(v("a", -10), v("b", -2))).getFirst().id());
  }

  @Test
  void differenceAndMovement() {
    var old = Ranking.sort(List.of(v("a", 10), v("b", 20)));
    var now = Ranking.sort(List.of(v("a", 30), v("b", 5)));
    assertEquals("前日比 +20.0 / 順位 ↑1", Ranking.comparison(now.getFirst(), old, true));
    assertTrue(Ranking.comparison(now.get(1), old, true).contains("↓1"));
  }

  @Test
  void firstSnapshot() {
    var row = Ranking.sort(List.of(v("a", 1))).getFirst();
    assertEquals("前日データなし", Ranking.comparison(row, List.of(), false));
    assertEquals("前日対象外", Ranking.comparison(row, List.of(), true));
  }

  @Test
  void renameUsesUuid() {
    var row = new Ranking.Row("same", "new", BigDecimal.TEN, 1);
    var old = new Ranking.Row("same", "old", BigDecimal.ONE, 1);
    assertTrue(Ranking.comparison(row, List.of(old), true).contains("+9"));
  }

  @Test
  void sqliteRoundTripAndDuplicateProtection() throws Exception {
    var file = temp.resolve("r.db");
    var rows = Ranking.sort(List.of(v("a", 12.5)));
    var time = Instant.now();
    try (var db = new Store(file)) {
      assertTrue(
          db.save("2026-11-01", time, time, Map.of("player", rows), List.of("{}"), "2026-11-08"));
      assertFalse(
          db.save(
              "2026-11-01",
              time,
              time,
              Map.of("player", List.of()),
              List.of("changed"),
              "2026-11-15"));
      assertEquals(rows, db.rows("2026-11-01", "player"));
      assertEquals("2026-11-08", db.metadata("next-week"));
      var delivery = db.pending().getFirst();
      assertTrue(db.claim(delivery));
      assertFalse(db.claim(delivery));
      db.state(delivery, "SENT");
      assertTrue(db.pending().isEmpty());
    }
    try (var db = new Store(file)) {
      assertEquals(rows, db.rows("2026-11-01", "player"));
      assertEquals("SENT=1", db.status());
    }
  }

  @Test
  void crashDoesNotResendAmbiguousPost() throws Exception {
    var file = temp.resolve("r.db");
    try (var db = new Store(file)) {
      db.save("week", Instant.now(), Instant.now(), Map.of(), List.of("{}"), "next");
      db.claim(db.pending().getFirst());
    }
    try (var db = new Store(file)) {
      assertTrue(db.pending().isEmpty());
      assertEquals("UNCERTAIN=1", db.status());
    }
  }

  @Test
  void unsentPartsRemainPending() throws Exception {
    try (var db = new Store(temp.resolve("r.db"))) {
      db.save("week", Instant.now(), Instant.now(), Map.of(), List.of("one", "two"), "next");
      var first = db.pending().getFirst();
      db.claim(first);
      db.state(first, "SENT");
      assertEquals("two", db.pending().getFirst().payload());
    }
  }

  @Test
  void embedsRespectLimitsAndSuppressMentions() {
    var json =
        com.google.gson.JsonParser.parseString(
                Embed.payload("t".repeat(300), "x".repeat(7000), 123))
            .getAsJsonObject();
    var e = json.getAsJsonArray("embeds").get(0).getAsJsonObject();
    assertTrue(e.get("title").getAsString().length() <= 256);
    assertTrue(e.get("description").getAsString().length() <= 4096);
    assertTrue(json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
  }

  @Test
  void paginatedRankings() {
    List<Ranking.Value> values = new ArrayList<>();
    for (int i = 0; i < 50; i++)
      values.add(
          new Ranking.Value("id" + i, "名".repeat(100), new BigDecimal("1000000000000000000")));
    var pages = Embed.ranking("日次", Ranking.sort(values), List.of(), false, 50, "week");
    assertTrue(pages.size() > 1);
    for (var p : pages)
      assertTrue(
          com.google.gson.JsonParser.parseString(p)
                  .getAsJsonObject()
                  .getAsJsonArray("embeds")
                  .get(0)
                  .getAsJsonObject()
                  .get("description")
                  .getAsString()
                  .length()
              <= 4096);
  }

  @Test
  void webhookMustBeDiscordHttps() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Webhook.validate("http://discord.com/api/webhooks/1/token"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Webhook.validate("https://evil.example/api/webhooks/1/token"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Webhook.validate("https://discord.com@evil.example/api/webhooks/1/token"));
    assertDoesNotThrow(() -> Webhook.validate("https://discord.com/api/webhooks/123/token_abc"));
  }

  @Test
  void futureSchemaIsRejected() throws Exception {
    var file = temp.resolve("future.db");
    try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
        var stmt = db.createStatement()) {
      stmt.execute("PRAGMA user_version=99");
    }
    assertThrows(java.sql.SQLException.class, () -> new Store(file));
  }

  @Test
  void firstEmptyRankingStillExplainsMissingHistory() {
    var payload = Embed.ranking("日次", List.of(), List.of(), false, 10, "week").getFirst();
    assertTrue(payload.contains("前日データなし"));
  }
}
