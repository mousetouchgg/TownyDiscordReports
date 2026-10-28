package jp.tdrprobe;

import com.gmail.goosius.siegewar.enums.*;
import com.gmail.goosius.siegewar.events.*;
import com.gmail.goosius.siegewar.objects.Siege;
import com.palmergames.bukkit.towny.*;
import com.palmergames.bukkit.towny.command.*;
import com.palmergames.bukkit.towny.event.*;
import com.palmergames.bukkit.towny.object.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Real API + local HTTP integration fixture. Enabled only in the isolated dev Compose. */
public final class Probe extends JavaPlugin {
  private HttpServer http;
  private JavaPlugin target;
  private Town town;
  private Nation nation;
  private final List<String> received = new CopyOnWriteArrayList<>();

  @Override
  public void onEnable() {
    if (!"true".equals(System.getenv("TDR_ALLOW_PROBE"))
        || !Files.exists(Path.of(".tdr-development"))) {
      getLogger().severe("Development guard missing; probe disabled");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    target = (JavaPlugin) Bukkit.getPluginManager().getPlugin("TownyDiscordReports");
    later(
        40,
        () -> {
          http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
          http.createContext(
              "/hook",
              e -> {
                received.add(
                    new String(
                        e.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                byte[] body = "{}".getBytes();
                e.sendResponseHeaders(200, body.length);
                e.getResponseBody().write(body);
                e.close();
              });
          http.start();
          target
              .getConfig()
              .set(
                  "webhook-url",
                  "https://discord.com/api/webhooks/123/development_dummy_no_real_token");
          target.getConfig().set("debug", true);
          target.saveConfig();
          command("tdr reload");
          later(40, this::run);
        });
  }

  private void run() throws Exception {
    var field = target.getClass().getDeclaredField("webhook");
    field.setAccessible(true);
    var type = Class.forName("jp.tdr.Webhook", true, target.getClass().getClassLoader());
    var constructor = type.getDeclaredConstructor(URI.class, int.class);
    constructor.setAccessible(true);
    ((AutoCloseable) field.get(target)).close();
    field.set(
        target,
        constructor.newInstance(
            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/hook"), 3));
    // Seed an already-due day only inside this local-HTTP fixture. Run DB calls on TDR's worker.
    var workerField = target.getClass().getDeclaredField("worker");
    workerField.setAccessible(true);
    var storeField = target.getClass().getDeclaredField("store");
    storeField.setAccessible(true);
    var store = storeField.get(target);
    var metadata = store.getClass().getMethod("metadata", String.class, String.class);
    var schedule =
        new jp.tdr.DailySchedule(java.time.LocalTime.NOON, java.time.ZoneId.of("Asia/Tokyo"));
    ((java.util.concurrent.Executor) workerField.get(target))
        .execute(
            () -> {
              try {
                metadata.invoke(
                    store,
                    "next-day",
                    schedule.due(java.time.Instant.now()).toLocalDate().toString());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    var ds = TownyUniverse.getInstance().getDataSource();
    String suffix = Long.toString(System.currentTimeMillis() % 1000000);
    var resident = ds.newResident("TDR_A" + suffix, UUID.randomUUID());
    var world = Bukkit.getWorlds().getFirst();
    var location = new Location(world, 160, world.getHighestBlockYAt(160, 160) + 1, 160);
    town =
        TownCommand.newTown(
            TownyAPI.getInstance().getTownyWorld(world),
            "TDR_T" + suffix,
            resident,
            new Coord(10, 10),
            location,
            null);
    nation = NationCommand.newNation("TDR_N" + suffix, town);
    nation.getAccount().setBalance(12345, "TDR development integration fixture");
    var heir = ds.newResident("TDR_B" + suffix, UUID.randomUUID());
    heir.setTown(town);
    town.setMayor(heir);
    town.save();
    var siege = new Siege(town);
    siege.setSiegeType(SiegeType.CONQUEST);
    siege.setStatus(SiegeStatus.IN_PROGRESS);
    siege.setAttacker(nation);
    siege.setDefender(town);
    siege.setFlagLocation(location);
    Bukkit.getPluginManager()
        .callEvent(new SiegeWarStartEvent(siege, town, null, "Development fixture"));
    siege.setStatus(SiegeStatus.ATTACKER_CLOSE_WIN);
    siege.setSiegeWinner(SiegeSide.ATTACKERS);
    siege.setAttackerName(nation.getName());
    siege.setDefenderName(town.getName());
    siege.setEndedAtMillis(System.currentTimeMillis());
    Bukkit.getPluginManager().callEvent(new SiegeEndEvent(siege));
    command("tdr status");
    command("tdr ranking nation");
    command("tdr testdiscord");
    command("tdr send daily");
    later(
        100,
        () -> {
          command("tdr send daily");
          ds.removeNation(nation, DeleteNationEvent.Cause.ADMIN_COMMAND, Bukkit.getConsoleSender());
          ds.removeTown(
              town, DeleteTownEvent.Cause.ADMIN_COMMAND, Bukkit.getConsoleSender(), false);
          later(100, this::verify);
        });
  }

  private void verify() throws Exception {
    List<String> expected =
        List.of(
            "新国家",
            "国家が滅亡",
            "町が設立",
            "町が削除",
            "国王が交代",
            "攻城戦が開始",
            "攻城戦が終了",
            "プレイヤー所持金ランキング",
            "国家銀行残高ランキング",
            "Discord通知テスト");
    List<String> missing =
        expected.stream()
            .filter(t -> received.stream().noneMatch(body -> body.contains(t)))
            .toList();
    long daily = received.stream().filter(body -> body.contains("国家銀行残高ランキング")).count();
    if (!missing.isEmpty() || daily != 1)
      throw new IllegalStateException("Missing=" + missing + " dailyCount=" + daily);
    Files.createDirectories(getDataFolder().toPath());
    Files.write(getDataFolder().toPath().resolve("received.jsonl"), received);
    getLogger()
        .info(
            "PROBE PASS: Towny lifecycle, confirmed succession, SiegeWar synthetic events, nation"
                + " balance, daily dedup, local HTTP ("
                + received.size()
                + " requests)");
    command("tdr status");
    // Restore safe blank webhook before stopping the fixture server.
    target.getConfig().set("webhook-url", "");
    target.saveConfig();
    command("tdr reload");
  }

  private void command(String text) {
    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text);
  }

  interface Checked {
    void run() throws Exception;
  }

  private void later(long ticks, Checked action) {
    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              try {
                action.run();
              } catch (Exception e) {
                getLogger().severe("PROBE FAIL: " + e);
                e.printStackTrace();
              }
            },
            ticks);
  }

  @Override
  public void onDisable() {
    if (http != null) http.stop(0);
  }
}
