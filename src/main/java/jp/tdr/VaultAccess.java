package jp.tdr;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** Loaded only when Vault is enabled; keeps optional API classes outside the core. */
final class VaultAccess {
  private VaultAccess() {}

  static EconomyCapture.Wallet find() {
    var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
    if (registration == null) return null;
    var provider = registration.getProvider();
    return new EconomyCapture.Wallet() {
      public String getName() {
        return provider.getName();
      }

      public boolean hasAccount(OfflinePlayer player) {
        return provider.hasAccount(player);
      }

      public double getBalance(OfflinePlayer player) {
        return provider.getBalance(player);
      }
    };
  }
}
