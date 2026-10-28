package jp.tdr;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Blocking HTTP runs only on the I/O worker. No exception messages contain credentials in logs. */
public final class Webhook implements AutoCloseable {
  public enum Result {
    SENT,
    FAILED,
    UNCERTAIN
  }

  private final HttpClient http;
  private final URI uri;
  private final int attempts, delay, timeout;

  public Webhook(String url, int attempts, int delay, int timeout) {
    uri = url.isBlank() ? null : validate(url);
    this.attempts = attempts;
    this.delay = delay;
    this.timeout = timeout;
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeout))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  static URI validate(String value) {
    var u = URI.create(value);
    if (!"https".equals(u.getScheme())
        || !"discord.com".equals(u.getHost())
        || u.getPort() != -1
        || u.getUserInfo() != null
        || !u.getPath().matches("/api/webhooks/[0-9]+/[A-Za-z0-9_-]+"))
      throw new IllegalArgumentException("Invalid webhook configuration");
    return u;
  }

  // Package-private injection for local HTTP tests only. Never configurable by users.
  Webhook(URI local, int attempts) {
    uri = local;
    this.attempts = attempts;
    delay = 0;
    timeout = 2;
    http = HttpClient.newHttpClient();
  }

  public boolean enabled() {
    return uri != null;
  }

  public Result send(String payload) {
    if (uri == null) return Result.FAILED;
    URI target = URI.create(uri.toString().split("\\?", 2)[0] + "?wait=true");
    for (int i = 0; i < attempts; i++) {
      try {
        var request =
            HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("User-Agent", "TownyDiscordReports/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code >= 200 && code < 300) return Result.SENT;
        if (code == 429) {
          if (i + 1 == attempts) return Result.FAILED;
          double seconds = retryAfter(response);
          // Do not retry earlier than Discord requests; very long delays require operator
          // intervention.
          if (seconds > 300) return Result.FAILED;
          Thread.sleep(Math.max(1, (long) Math.ceil(seconds * 1000)));
          continue;
        }
        // 5xx can occur after accepting a message. Avoid automatic duplicate posts.
        if (code >= 500) return Result.UNCERTAIN;
        return Result.FAILED;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Result.UNCERTAIN;
      } catch (Exception e) {
        return Result.UNCERTAIN;
      }
    }
    return Result.FAILED;
  }

  private double retryAfter(HttpResponse<String> r) {
    double seconds = delay;
    try {
      seconds =
          Math.max(seconds, Double.parseDouble(r.headers().firstValue("Retry-After").orElse("0")));
    } catch (Exception ignored) {
    }
    try {
      seconds =
          Math.max(
              seconds,
              JsonParser.parseString(r.body()).getAsJsonObject().get("retry_after").getAsDouble());
    } catch (Exception ignored) {
    }
    return Double.isFinite(seconds) && seconds >= 0 ? seconds : delay;
  }

  @Override
  public void close() {
    http.shutdownNow();
  }
}
