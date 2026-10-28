package jp.tdr;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class TownyDiscordReports extends JavaPlugin implements TabCompleter {
  private volatile Settings settings;
  private volatile boolean ready, stopping;
  private volatile String deliveryState = "初期化中";
  private ThreadPoolExecutor worker;
  private Store store;
  private Webhook webhook;
  private TownyEvents events;
  private final AtomicBoolean dailyBusy = new AtomicBoolean();

  @Override
  public void onEnable() {
    saveDefaultConfig();
    try {
      settings = loadSettings();
    } catch (Exception e) {
      getLogger().severe("設定が不正です。URL、日時、SQLiteパス、数値範囲を確認してください。");
      Bukkit.getPluginManager().disablePlugin(this);
      return;
    }
    worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            r -> {
              var t = new Thread(r, "TDR-IO");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy()) {
          @Override
          protected void terminated() {
            if (webhook != null) webhook.close();
            if (store != null)
              try {
                store.close();
              } catch (Exception e) {
                getLogger().warning("SQLite終了失敗");
              }
          }
        };
    submit(
        () -> {
          try {
            store = new Store(settings.database());
            store.ensureDailyCursor(settings.schedule(), Instant.now());
            webhook = settings.newWebhook();
            deliveryState = store.status();
            ready = true;
          } catch (Exception e) {
            getLogger()
                .severe("SQLiteまたはHTTP初期化失敗（" + e.getClass().getSimpleName() + "）。通知を停止します。");
          }
        });
    events = new TownyEvents(this);
    Bukkit.getPluginManager().registerEvents(events, this);
    if (Bukkit.getPluginManager().isPluginEnabled("SiegeWar"))
      Bukkit.getPluginManager().registerEvents(new SiegeEvents(this), this);
    Objects.requireNonNull(getCommand("tdr")).setExecutor(this);
    getCommand("tdr").setTabCompleter(this);
    Bukkit.getScheduler().runTaskTimer(this, () -> events.refresh(true), 20, 20);
    Bukkit.getScheduler().runTaskTimer(this, () -> daily(null), 40, 1200);
    getLogger()
        .info(
            "Towny連携有効 / SiegeWar="
                + Bukkit.getPluginManager().isPluginEnabled("SiegeWar")
                + " / Webhook="
                + !settings.webhook().isBlank());
  }

  private Settings loadSettings() throws Exception {
    var config = new org.bukkit.configuration.file.YamlConfiguration();
    config.load(new java.io.File(getDataFolder(), "config.yml"));
    return Settings.read(config, getDataFolder().toPath());
  }

  private boolean submit(Runnable task) {
    if (stopping || worker == null) return false;
    try {
      worker.execute(
          () -> {
            try {
              task.run();
            } catch (Exception e) {
              getLogger().warning("I/O処理失敗（" + e.getClass().getSimpleName() + "）");
            }
          });
      return true;
    } catch (RejectedExecutionException e) {
      getLogger().warning("I/Oキュー満杯のため処理を受付できませんでした。");
      return false;
    }
  }

  String time(long epoch) {
    return Instant.ofEpochMilli(epoch)
        .atZone(settings.schedule().zone())
        .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
  }

  void onMain(Runnable task) {
    if (Bukkit.isPrimaryThread()) task.run();
    else if (!stopping) Bukkit.getScheduler().runTask(this, task);
  }

  void notifyEvent(String kind, String title, String body, int color) {
    var config = settings;
    if (!ready || stopping || config.webhook().isBlank() || !config.notifications().contains(kind))
      return;
    String payload =
        Embed.payload(title, Embed.clean(body) + "\n日時：" + time(System.currentTimeMillis()), color);
    submit(
        () -> {
          var result = webhook.send(payload);
          if (result != Webhook.Result.SENT)
            getLogger().warning("Discordイベント送信：" + result + " / " + kind);
          else if (config.debug()) getLogger().info("Discordイベント送信完了：" + kind);
        });
  }

  private void reply(CommandSender sender, String text) {
    if (sender != null) onMain(() -> sender.sendMessage("[TDR] " + text));
  }

  private void daily(CommandSender sender) {
    var config = settings;
    if (!ready || !config.rankings() || config.webhook().isBlank()) {
      reply(sender, "日次配信停止中（初期化・設定・Webhookを確認）");
      return;
    }
    if (!dailyBusy.compareAndSet(false, true)) {
      reply(sender, "日次処理は実行中です。");
      return;
    }
    boolean accepted =
        submit(
            () -> {
              try {
                // Retry only parts known never to have been accepted. FAILED/UNCERTAIN require
                // operator review.
                for (var d : store.pendingDaily()) deliver(d);
                var due = config.schedule().due(Instant.now());
                String cursor = store.ensureDailyCursor(config.schedule(), Instant.now());
                var date = LocalDate.parse(cursor);
                if (date.isAfter(due.toLocalDate())) {
                  reply(
                      sender,
                      "対象の未送信日なし（送信済み、または配信時刻前）。次回：" + config.schedule().next(Instant.now()));
                  return;
                }
                var scheduled =
                    date.atTime(config.schedule().time()).atZone(config.schedule().zone());
                String day = date.toString();
                String reportKey = DailySchedule.key(date);
                if (store.exists(reportKey)) {
                  store.metadata("next-day", date.plusDays(1).toString());
                  reply(sender, "対象日は作成済みです。配信状態をstatusで確認してください。");
                  return;
                }
                var capture = new CompletableFuture<EconomyCapture.Snapshot>();
                onMain(
                    () -> {
                      try {
                        capture.complete(EconomyCapture.collect());
                      } catch (Exception e) {
                        capture.completeExceptionally(e);
                      }
                    });
                var snapshot = capture.get(60, TimeUnit.SECONDS);
                String previous = DailySchedule.key(date.minusDays(1));
                boolean hasPrevious = store.exists(previous);
                List<String> payloads = new ArrayList<>();
                String label = day + "（実集計：" + time(System.currentTimeMillis()) + "）";
                payloads.addAll(
                    Embed.ranking(
                        "💰 プレイヤー所持金ランキング",
                        snapshot.players(),
                        store.rows(previous, "player"),
                        hasPrevious,
                        config.limit(),
                        label));
                payloads.addAll(
                    Embed.ranking(
                        "🏦 国家銀行残高ランキング",
                        snapshot.nations(),
                        store.rows(previous, "nation"),
                        hasPrevious,
                        config.limit(),
                        label));
                store.saveDaily(
                    date,
                    Instant.now(),
                    scheduled.toInstant(),
                    Map.of("player", snapshot.players(), "nation", snapshot.nations()),
                    payloads);
                if (snapshot.skipped() > 0)
                  getLogger().warning("残高取得不能・不正値を除外：" + snapshot.skipped() + "件");
                for (var d : store.pendingDaily()) deliver(d);
                reply(sender, "対象日 " + day + " の処理完了：" + deliveryState);
              } catch (Exception e) {
                getLogger().warning("日次処理失敗（" + e.getClass().getSimpleName() + "）");
                reply(sender, "日次処理に失敗しました。ログを確認してください。");
              } finally {
                dailyBusy.set(false);
              }
            });
    if (!accepted) dailyBusy.set(false);
  }

  private void deliver(Store.Delivery d) throws Exception {
    if (stopping || !webhook.enabled() || !store.claim(d)) return;
    var result = webhook.send(d.payload());
    store.state(d, result.name());
    deliveryState = store.status();
    if (result != Webhook.Result.SENT)
      getLogger()
          .warning(
              "日次Discord送信：" + result + " 対象日=" + d.week() + " 部分=" + d.part() + "。自動再送を停止しました。");
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("townydiscordreports.admin")) {
      sender.sendMessage("権限がありません。");
      return true;
    }
    try {
      if (Arrays.equals(args, new String[] {"reload"})) {
        var next = loadSettings();
        if (!next.database().equals(settings.database())) {
          reply(sender, "SQLite保存先の変更には再起動が必要です。現在の設定を維持します。");
          return true;
        }
        if (dailyBusy.get()) {
          reply(sender, "日次処理完了後にreloadしてください。");
          return true;
        }
        submit(
            () -> {
              Webhook replacement = next.newWebhook();
              if (webhook != null) webhook.close();
              webhook = replacement;
              settings = next;
              reply(sender, "設定を再読み込みしました。");
            });
        return true;
      }
      if (Arrays.equals(args, new String[] {"status"})) {
        var eco = EconomyCapture.economy();
        reply(
            sender,
            "Towny="
                + Bukkit.getPluginManager().isPluginEnabled("Towny")
                + " / SiegeWar="
                + Bukkit.getPluginManager().isPluginEnabled("SiegeWar")
                + " / Vault="
                + (eco == null ? "未取得" : eco.getName()));
        reply(
            sender,
            "SQLite="
                + ready
                + " / Webhook="
                + !settings.webhook().isBlank()
                + " / ランキング="
                + settings.rankings()
                + " / 配信=毎日 "
                + settings.schedule().time()
                + " / 次回="
                + settings.schedule().next(Instant.now()));
        reply(sender, "配信状態：" + deliveryState);
        return true;
      }
      if (args.length == 2
          && args[0].equals("ranking")
          && Set.of("player", "nation").contains(args[1])) {
        var snapshot = EconomyCapture.collect();
        var rows = args[1].equals("player") ? snapshot.players() : snapshot.nations();
        reply(
            sender,
            "現在のランキング / "
                + (args[1].equals("player") ? snapshot.playerState() : snapshot.nationState()));
        for (var row : rows.stream().limit(settings.limit()).toList())
          reply(sender, row.rank() + ". " + row.name() + "：" + row.balance().toPlainString());
        if (rows.isEmpty()) reply(sender, "対象なし");
        return true;
      }
      if (args.length == 2
          && args[0].equals("send")
          && Set.of("daily", "weekly").contains(args[1])) {
        if (args[1].equals("weekly"))
          reply(sender, "weeklyは旧名です。日次配信として処理します。今後は /tdr send daily を使用してください。");
        daily(sender);
        return true;
      }
      if (Arrays.equals(args, new String[] {"testdiscord"})) {
        if (!ready || settings.webhook().isBlank()) {
          reply(sender, "Webhook未設定または初期化中です。");
          return true;
        }
        submit(
            () ->
                reply(
                    sender,
                    "Discordテスト："
                        + webhook.send(
                            Embed.payload(
                                "🔔 Discord通知テスト", "TownyDiscordReportsの接続確認です。", 0x3498db))));
        return true;
      }
    } catch (Exception e) {
      reply(sender, "設定または処理が不正です。現在の設定は維持されます（" + e.getClass().getSimpleName() + "）。");
      return true;
    }
    return false;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (!sender.hasPermission("townydiscordreports.admin")) return List.of();
    List<String> options =
        args.length == 1
            ? List.of("reload", "status", "ranking", "send", "testdiscord")
            : args.length == 2 && args[0].equals("ranking")
                ? List.of("player", "nation")
                : args.length == 2 && args[0].equals("send") ? List.of("daily") : List.of();
    return options.stream().filter(s -> s.startsWith(args[args.length - 1])).toList();
  }

  @Override
  public void onDisable() {
    stopping = true;
    ready = false;
    Bukkit.getScheduler().cancelTasks(this);
    if (worker == null) return;
    // Clear queued events; persist SENDING as UNCERTAIN on next start if interrupted.
    worker.getQueue().clear();
    worker.shutdownNow();
    try {
      if (!worker.awaitTermination(3, TimeUnit.SECONDS))
        getLogger().warning("I/O処理の停止待ちが時間切れになりました。");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (webhook != null) webhook.close();
    getLogger().info("TDRリソース終了処理完了");
  }
}
