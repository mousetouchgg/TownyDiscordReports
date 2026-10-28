# API確認記録（2026-10-28）

コンパイル対象JARのメタデータ、公式ソース、開発用Paperでの挙動を確認した。サーバー設定や認証情報は公開対象に含めない。

|対象|確認結果|
|---|---|
|Paper|公式26.3 build 227を取得し、起動ログで確認|
|Towny|0.103.2.0|
|SiegeWar|3.7.0|
|VaultUnlocked|2.20.3、従来の`net.milkbowl.vault.economy.Economy`も収録|
|EssentialsX|2.22.1-dev+27-e70bdb8|
|Java|ビルド・検証はCorretto 25。対象PaperはJava 25必須|

公式Paper JAR SHA-256: `416b7d105b1d0192df4afa4b29d2800d9dbeda092a82fbc5b511248a54adde15`。

## Towny

確認ソース: [Towny 0.103.2.0](https://github.com/TownyAdvanced/Towny/tree/0.103.2.0)。調査コミット`db92beace5fc73a14a3484facbdcbe945f6c4691`。導入JARでコンパイル・Paper上で起動確認した。

- `com.palmergames.bukkit.towny.event.NewNationEvent#getNation()`
- `NewTownEvent#getTown()`
- `DeleteNationEvent#getNationName()/getNationUUID()/getLeader()`。首都は含まれない。
- `DeleteTownEvent#getTownName()/getTownUUID()/getMayor()`。旧所属国家は含まれない。
- `PreDeleteNationEvent`、`PreDeleteTownEvent`で情報を保持。管理削除などは前イベントを省略するため、起動時および毎秒のキャッシュも使う。キャッシュがなければ「取得不能」と表示する。
- `com.palmergames.bukkit.towny.event.nation.NationKingChangeEvent`は存在するがキャンセル可能で、変更確定より前に発火。追加の支払い確認が後続する場合もある。実装は毎秒、国家UUIDと国王UUIDの確定状態を照合する。キャンセルの誤通知を避け、イベントを経由しない継承も検出する。停止中や1秒未満に複数回交代した中間状態は検出できない。
- 銀行は`TownyAPI#getNations()`、`Nation#getAccount().getHoldingBalance()`。`TownyEconomyHandler.isActive()`がfalseなら取得しない。例外と非有限値は除外する。

## SiegeWar

確認ソース: [SiegeWar 3.7.0](https://github.com/TownyAdvanced/SiegeWar/tree/3.7.0)。調査コミット`8997342793c438a6d6cd55b8a78b70888eafb425`。

- 開始: `com.gmail.goosius.siegewar.events.SiegeWarStartEvent#getSiege()`。
- 終了: `SiegeEndEvent`。`SiegeWarSiegeCompletionUtil#setCommonSiegeCompletionValues()`から発火し、終了日時と当事者名が保存済み。
- 終了時は`getAttackerName()/getDefenderName()/getBesiegedTownName()`を使う。消滅した町・国家オブジェクトの参照に依存しない。
- `Siege#getStatus()`の`attackersWon()/defendersWon()`で勝者を取得する。接戦・決定的勝利・圧勝・放棄・降伏の原始ステータスも併記する。
- SiegeWarの攻城戦を対象とする。BattleSessionの開始終了は攻城戦全体の開始終了と区別し、通知対象にしていない。終了と勝敗確定は同一Embedにまとめる。町の侵略・略奪は別機能で未実装。

## Vault / EssentialsX

[Vault経済API](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/Economy.java)には全プレイヤー列挙がない。
`Bukkit.getServicesManager().getRegistration(Economy.class)`からプロバイダーを取得し、`OfflinePlayer`版`hasAccount/getBalance`を使う。

[EssentialsX公式Vault実装](https://github.com/EssentialsX/Essentials/blob/2.x/Essentials/src/main/java/com/earth2me/essentials/economy/vault/VaultEconomyProvider.java)はUUID経由のユーザーデータを扱う。ただしVault経由でEssentialsの全ユーザーファイルを列挙できるわけではない。
対象は`Bukkit.getOfflinePlayers()`と`Bukkit.getOnlinePlayers()`をUUIDで統合し、オンラインまたは`hasPlayedBefore()`がtrue、かつVaultアカウントが存在するプレイヤー。初回ログイン中は保存済みデータがなくても対象に含める。別ワールド保存先・削除済みプレイヤーデータ・未ログインのNPC口座・Essentialsユーザーデータだけが残る口座を網羅する保証はない。
全口座が必要なら、プロバイダー固有の列挙APIを別途検証して拡張する。

[Paper Java要件](https://docs.papermc.io/paper/getting-started/)、[公式APIプロジェクト構成](https://docs.papermc.io/paper/dev/project-setup/)、[Discordレート制限](https://github.com/discord/discord-api-docs/blob/main/developers/topics/rate-limits.mdx)も確認した。
