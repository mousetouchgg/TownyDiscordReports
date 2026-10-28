package jp.tdr;

import com.palmergames.bukkit.towny.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

final class EconomyCapture {
  private EconomyCapture() {}

  interface Wallet {
    String getName();

    boolean hasAccount(OfflinePlayer player);

    double getBalance(OfflinePlayer player);
  }

  static Wallet economy() {
    if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null;
    return VaultAccess.find();
  }

  record Snapshot(
      List<Ranking.Row> players,
      List<Ranking.Row> nations,
      int skipped,
      String playerState,
      String nationState) {}

  static Snapshot collect() {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Capture requires server thread");
    List<Ranking.Value> players = new ArrayList<>(), nations = new ArrayList<>();
    int skipped = 0;
    Wallet economy = economy();
    String playerState = economy == null ? "経済プロバイダーなし" : economy.getName();
    // First-session players may not have saved playerdata or hasPlayedBefore() yet.
    // Prefer live Player objects for current names and deduplicate both sources by UUID.
    Map<UUID, OfflinePlayer> candidates = new HashMap<>();
    for (var player : Bukkit.getOfflinePlayers()) candidates.put(player.getUniqueId(), player);
    for (var player : Bukkit.getOnlinePlayers()) candidates.put(player.getUniqueId(), player);
    if (economy != null)
      for (var player : candidates.values()) {
        try {
          if ((player.isOnline() || player.hasPlayedBefore()) && economy.hasAccount(player))
            players.add(
                Ranking.Value.of(
                    player.getUniqueId().toString(), player.getName(), economy.getBalance(player)));
        } catch (Exception e) {
          skipped++;
        }
      }
    boolean active = TownyEconomyHandler.isActive();
    if (active)
      for (var nation : TownyAPI.getInstance().getNations()) {
        try {
          nations.add(
              Ranking.Value.of(
                  nation.getUUID().toString(),
                  nation.getName(),
                  nation.getAccount().getHoldingBalance()));
        } catch (Exception e) {
          skipped++;
        }
      }
    return new Snapshot(
        Ranking.sort(players),
        Ranking.sort(nations),
        skipped,
        playerState,
        active ? "有効" : "Towny経済無効");
  }
}
