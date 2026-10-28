package jp.tdr;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebhookTest {
  @Test
  void retries429AndUsesWaitTrue() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new AtomicInteger();
    server.createContext(
        "/hook",
        e -> {
          assertEquals("wait=true", e.getRequestURI().getQuery());
          e.getRequestBody().readAllBytes();
          int code = requests.incrementAndGet() == 1 ? 429 : 200;
          byte[] body = (code == 429 ? "{\"retry_after\":0.01}" : "{}").getBytes();
          e.sendResponseHeaders(code, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    server.start();
    try (var hook =
        new Webhook(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"), 3)) {
      assertEquals(Webhook.Result.SENT, hook.send("{}"));
      assertEquals(2, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void serverErrorIsUncertainAndNeverBlindlyRetried() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var count = new AtomicInteger();
    server.createContext(
        "/",
        e -> {
          count.incrementAndGet();
          e.getRequestBody().readAllBytes();
          e.sendResponseHeaders(503, -1);
          e.close();
        });
    server.start();
    try (var hook =
        new Webhook(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), 3)) {
      assertEquals(Webhook.Result.UNCERTAIN, hook.send("{}"));
      assertEquals(1, count.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void disabledWebhookDoesNotSend() {
    try (var hook = new Webhook("", 3, 1, 2)) {
      assertFalse(hook.enabled());
      assertEquals(Webhook.Result.FAILED, hook.send("{}"));
    }
  }
}
