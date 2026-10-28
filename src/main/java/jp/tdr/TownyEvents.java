package jp.tdr;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.event.*;
import com.palmergames.bukkit.towny.object.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.event.*;

final class TownyEvents implements Listener {
  private final TownyDiscordReports plugin;

  private record NationInfo(String name, String kingId, String king, String capital) {}

  private record TownInfo(String name, String mayor, String nation) {}

  private final Map<UUID, NationInfo> nations = new HashMap<>();
  private final Map<UUID, TownInfo> towns = new HashMap<>();

  TownyEvents(TownyDiscordReports plugin) {
    this.plugin = plugin;
    refresh(false);
  }

  private NationInfo info(Nation n) {
    var k = n.getKing();
    return new NationInfo(
        n.getName(),
        k == null ? "" : k.getUUID().toString(),
        k == null ? "不明" : k.getName(),
        n.getCapital() == null ? "不明" : n.getCapital().getName());
  }

  private TownInfo info(Town t) {
    return new TownInfo(
        t.getName(),
        t.getMayor() == null ? "不明" : t.getMayor().getName(),
        t.getNationOrNull() == null ? "無所属" : t.getNationOrNull().getName());
  }

  private String nationText(NationInfo n) {
    return "国家名：" + n.name() + "\n国王：" + n.king() + "\n首都：" + n.capital();
  }

  private String townText(TownInfo t) {
    return "町名：" + t.name() + "\n町長：" + t.mayor() + "\n所属国家：" + t.nation();
  }

  void refresh(boolean notify) {
    // Poll confirmed state: catches succession without an event and avoids pre-change/cancelled
    // notifications.
    Set<UUID> current = new HashSet<>();
    for (var n : TownyAPI.getInstance().getNations()) {
      var next = info(n);
      current.add(n.getUUID());
      var old = nations.put(n.getUUID(), next);
      if (notify && old != null && !old.kingId().equals(next.kingId()))
        plugin.notifyEvent(
            "king-change",
            "👑 国王が交代しました",
            "国家名：" + next.name() + "\n旧国王：" + old.king() + "\n新国王：" + next.king(),
            0xf1c40f);
    }
    nations.keySet().retainAll(current);
    Set<UUID> liveTowns = new HashSet<>();
    for (var t : TownyAPI.getInstance().getTowns()) {
      towns.put(t.getUUID(), info(t));
      liveTowns.add(t.getUUID());
    }
    towns.keySet().retainAll(liveTowns);
  }

  private void main(Runnable job) {
    if (Bukkit.isPrimaryThread()) job.run();
    else Bukkit.getScheduler().runTask(plugin, job);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void nation(NewNationEvent e) {
    main(
        () -> {
          var n = e.getNation();
          var data = info(n);
          nations.put(n.getUUID(), data);
          plugin.notifyEvent(
              "nation-create",
              "👑 新国家が誕生しました！",
              nationText(data) + "\n建国日時：" + plugin.time(n.getRegistered()),
              0x2ecc71);
        });
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void beforeNation(PreDeleteNationEvent e) {
    main(() -> nations.put(e.getNation().getUUID(), info(e.getNation())));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void nationDelete(DeleteNationEvent e) {
    // Event contains stable name/UUID; capture leader name while still available.
    main(
        () -> {
          String leader = e.getLeader() == null ? "不明" : e.getLeader().getName();
          var old = nations.remove(e.getNationUUID());
          plugin.notifyEvent(
              "nation-delete",
              "🏴 国家が滅亡しました",
              "国家名："
                  + e.getNationName()
                  + "\n国王："
                  + leader
                  + "\n首都："
                  + (old == null ? "取得不能" : old.capital()),
              0xe74c3c);
        });
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void town(NewTownEvent e) {
    main(
        () -> {
          var t = e.getTown();
          var data = info(t);
          towns.put(t.getUUID(), data);
          plugin.notifyEvent(
              "town-create",
              "🏘 町が設立されました",
              townText(data) + "\n設立日時：" + plugin.time(t.getRegistered()),
              0x1abc9c);
        });
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void beforeTown(PreDeleteTownEvent e) {
    main(() -> towns.put(e.getTown().getUUID(), info(e.getTown())));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void townDelete(DeleteTownEvent e) {
    main(
        () -> {
          String mayor = e.getMayor() == null ? "不明" : e.getMayor().getName();
          var old = towns.remove(e.getTownUUID());
          plugin.notifyEvent(
              "town-delete",
              "🏚 町が削除されました",
              "町名："
                  + e.getTownName()
                  + "\n町長："
                  + mayor
                  + "\n所属国家："
                  + (old == null ? "取得不能" : old.nation()),
              0xe67e22);
        });
  }
}
