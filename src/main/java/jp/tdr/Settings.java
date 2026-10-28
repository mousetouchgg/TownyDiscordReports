package jp.tdr;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.bukkit.configuration.file.FileConfiguration;

record Settings(
    String webhook,
    int attempts,
    int delay,
    int timeout,
    boolean rankings,
    int limit,
    DailySchedule schedule,
    Path database,
    boolean debug,
    Set<String> notifications) {
  static Settings read(FileConfiguration c, Path folder) {
    String url =
        Optional.ofNullable(System.getenv("TDR_WEBHOOK_URL"))
            .orElse(c.getString("webhook-url", ""))
            .trim();
    if (!url.isBlank()) Webhook.validate(url);
    int attempts = c.getInt("retry.attempts", 3),
        delay = c.getInt("retry.delay-seconds", 2),
        timeout = c.getInt("retry.timeout-seconds", 10),
        limit = c.getInt("ranking.limit", 10);
    if (attempts < 1
        || attempts > 10
        || delay < 0
        || delay > 300
        || timeout < 1
        || timeout > 60
        || limit < 1
        || limit > 50) throw new IllegalArgumentException();
    var schedule =
        new DailySchedule(
            LocalTime.parse(c.getString("schedule.time", "12:00")),
            ZoneId.of(c.getString("schedule.timezone", "Asia/Tokyo")));
    Path base = folder.toAbsolutePath().normalize(),
        relative = Path.of(c.getString("sqlite-path", "reports.db"));
    Path db = base.resolve(relative).normalize();
    if (relative.isAbsolute() || !db.startsWith(base) || db.equals(base))
      throw new IllegalArgumentException();
    Set<String> flags = new HashSet<>();
    for (String key :
        List.of(
            "nation-create",
            "nation-delete",
            "town-create",
            "town-delete",
            "king-change",
            "siege-start",
            "siege-end")) if (c.getBoolean("notifications." + key, true)) flags.add(key);
    return new Settings(
        url,
        attempts,
        delay,
        timeout,
        c.getBoolean("ranking.enabled", true),
        limit,
        schedule,
        db,
        c.getBoolean("debug", false),
        Set.copyOf(flags));
  }

  Webhook newWebhook() {
    return new Webhook(webhook, attempts, delay, timeout);
  }
}
