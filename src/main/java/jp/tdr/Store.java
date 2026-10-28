package jp.tdr;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** All calls belong to the single I/O worker, never the server thread. */
public final class Store implements AutoCloseable {
  private final Connection db;

  public record Delivery(String week, int part, String payload) {}

  public Store(Path path) throws Exception {
    Files.createDirectories(path.toAbsolutePath().getParent());
    db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    try (var s = db.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA busy_timeout=5000");
      int version;
      try (var r = s.executeQuery("PRAGMA user_version")) {
        r.next();
        version = r.getInt(1);
      }
      if (version > 1) throw new SQLException("Unsupported schema version");
      if (version == 0) {
        db.setAutoCommit(false);
        try {
          try (var in = Store.class.getResourceAsStream("/schema-v1.sql")) {
            for (String sql :
                new String(
                        Objects.requireNonNull(in).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)
                    .split(";")) if (!sql.isBlank()) s.execute(sql);
          }
          s.execute("PRAGMA user_version=1");
          db.commit();
        } catch (Exception e) {
          db.rollback();
          throw e;
        } finally {
          db.setAutoCommit(true);
        }
      }
      // A crash may have happened after Discord accepted the POST.
      s.executeUpdate("UPDATE deliveries SET state='UNCERTAIN' WHERE state='SENDING'");
    } catch (Exception e) {
      db.close();
      throw e;
    }
  }

  public String metadata(String key) throws SQLException {
    try (var s = db.prepareStatement("SELECT value FROM metadata WHERE key=?")) {
      s.setString(1, key);
      try (var r = s.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    }
  }

  public void metadata(String key, String value) throws SQLException {
    try (var s =
        db.prepareStatement(
            "INSERT INTO metadata VALUES(?,?) ON CONFLICT(key) DO UPDATE SET"
                + " value=excluded.value")) {
      s.setString(1, key);
      s.setString(2, value);
      s.executeUpdate();
    }
  }

  public boolean exists(String week) throws SQLException {
    try (var s = db.prepareStatement("SELECT 1 FROM reports WHERE week=?")) {
      s.setString(1, week);
      try (var r = s.executeQuery()) {
        return r.next();
      }
    }
  }

  public List<Ranking.Row> rows(String week, String kind) throws SQLException {
    List<Ranking.Row> result = new ArrayList<>();
    try (var s =
        db.prepareStatement(
            "SELECT id,name,balance,rank FROM balances WHERE week=? AND kind=? ORDER BY rank")) {
      s.setString(1, week);
      s.setString(2, kind);
      try (var r = s.executeQuery()) {
        while (r.next())
          result.add(
              new Ranking.Row(
                  r.getString(1), r.getString(2), new BigDecimal(r.getString(3)), r.getInt(4)));
      }
    }
    return result;
  }

  public boolean save(
      String week,
      Instant captured,
      Instant scheduled,
      Map<String, List<Ranking.Row>> rankings,
      List<String> payloads,
      String nextWeek)
      throws SQLException {
    return saveReport(week, captured, scheduled, rankings, payloads, nextWeek, "next-week");
  }

  public String ensureDailyCursor(DailySchedule schedule, Instant now) throws SQLException {
    String cursor = metadata("next-day");
    if (cursor == null) {
      // New installations / weekly upgrades start at the next scheduled noon, without backfill.
      cursor = schedule.next(now).toLocalDate().toString();
      metadata("next-day", cursor);
    }
    return cursor;
  }

  public boolean saveDaily(
      java.time.LocalDate date,
      Instant captured,
      Instant scheduled,
      Map<String, List<Ranking.Row>> rankings,
      List<String> payloads)
      throws SQLException {
    return saveReport(
        DailySchedule.key(date),
        captured,
        scheduled,
        rankings,
        payloads,
        date.plusDays(1).toString(),
        "next-day");
  }

  private boolean saveReport(
      String week,
      Instant captured,
      Instant scheduled,
      Map<String, List<Ranking.Row>> rankings,
      List<String> payloads,
      String nextDate,
      String cursorKey)
      throws SQLException {
    db.setAutoCommit(false);
    try {
      try (var s = db.prepareStatement("INSERT OR IGNORE INTO reports VALUES(?,?,?)")) {
        s.setString(1, week);
        s.setString(2, captured.toString());
        s.setString(3, scheduled.toString());
        if (s.executeUpdate() == 0) {
          db.rollback();
          return false;
        }
      }
      try (var s = db.prepareStatement("INSERT INTO balances VALUES(?,?,?,?,?,?)")) {
        for (var entry : rankings.entrySet())
          for (var row : entry.getValue()) {
            s.setString(1, week);
            s.setString(2, entry.getKey());
            s.setString(3, row.id());
            s.setString(4, row.name());
            s.setString(5, row.balance().toPlainString());
            s.setInt(6, row.rank());
            s.addBatch();
          }
        s.executeBatch();
      }
      try (var s = db.prepareStatement("INSERT INTO deliveries VALUES(?,?,?,'PENDING')")) {
        for (int i = 0; i < payloads.size(); i++) {
          s.setString(1, week);
          s.setInt(2, i);
          s.setString(3, payloads.get(i));
          s.addBatch();
        }
        s.executeBatch();
      }
      metadata(cursorKey, nextDate);
      db.commit();
      return true;
    } catch (SQLException e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public List<Delivery> pending() throws SQLException {
    return pending("%");
  }

  public List<Delivery> pendingDaily() throws SQLException {
    return pending("daily:%");
  }

  private List<Delivery> pending(String pattern) throws SQLException {
    List<Delivery> result = new ArrayList<>();
    try (var s =
        db.prepareStatement(
            "SELECT week,part,payload FROM deliveries WHERE state='PENDING' AND week LIKE ? ORDER"
                + " BY week,part")) {
      s.setString(1, pattern);
      try (var r = s.executeQuery()) {
        while (r.next()) result.add(new Delivery(r.getString(1), r.getInt(2), r.getString(3)));
      }
    }
    return result;
  }

  public boolean claim(Delivery d) throws SQLException {
    try (var s =
        db.prepareStatement(
            "UPDATE deliveries SET state='SENDING' WHERE week=? AND part=? AND state='PENDING'")) {
      s.setString(1, d.week());
      s.setInt(2, d.part());
      return s.executeUpdate() == 1;
    }
  }

  public void state(Delivery d, String state) throws SQLException {
    try (var s = db.prepareStatement("UPDATE deliveries SET state=? WHERE week=? AND part=?")) {
      s.setString(1, state);
      s.setString(2, d.week());
      s.setInt(3, d.part());
      s.executeUpdate();
    }
  }

  public String status() throws SQLException {
    List<String> out = new ArrayList<>();
    try (var s = db.createStatement();
        var r = s.executeQuery("SELECT state,count(*) FROM deliveries GROUP BY state")) {
      while (r.next()) out.add(r.getString(1) + "=" + r.getInt(2));
    }
    return out.isEmpty() ? "履歴なし" : String.join(", ", out);
  }

  @Override
  public void close() throws SQLException {
    db.close();
  }
}
