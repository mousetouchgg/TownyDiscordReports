package jp.tdr;

import com.gmail.goosius.siegewar.events.*;
import org.bukkit.event.*;

final class SiegeEvents implements Listener {
  private final TownyDiscordReports plugin;

  SiegeEvents(TownyDiscordReports plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void start(SiegeWarStartEvent e) {
    plugin.onMain(
        () -> {
          var s = e.getSiege();
          plugin.notifyEvent(
              "siege-start",
              "⚔ 攻城戦が開始されました",
              "攻撃側："
                  + s.getAttackingNationIfPossibleElseTown().getName()
                  + "\n防衛側："
                  + s.getDefendingNationIfPossibleElseTown().getName()
                  + "\n対象の町："
                  + e.getTargetTown().getName()
                  + "\n開始日時："
                  + plugin.time(s.getStartedAtMillis())
                  + "\n結果：進行中",
              0xc0392b);
        });
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void end(SiegeEndEvent e) {
    plugin.onMain(
        () -> {
          var status = e.getSiege().getStatus();
          String result = status.attackersWon() ? "攻撃側勝利" : status.defendersWon() ? "防衛側勝利" : "未確定";
          plugin.notifyEvent(
              "siege-end",
              "🏳 攻城戦が終了しました・勝敗確定",
              "攻撃側："
                  + e.getAttackerName()
                  + "\n防衛側："
                  + e.getDefenderName()
                  + "\n対象の町："
                  + e.getBesiegedTownName()
                  + "\n終了日時："
                  + plugin.time(e.getSiege().getEndedAtMillis())
                  + "\n結果："
                  + result
                  + "（"
                  + status.name()
                  + "）",
              0x9b59b6);
        });
  }
}
