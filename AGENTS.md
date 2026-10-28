# TownyDiscordReports 開発規約

- 日本語で進捗と結果を報告する。既存ファイルを上書き・削除する前に用途を確認する。
- 本番OCI、SSH鍵、本番Webhook、Discord Bot、本番DBには接続しない。
- `plugins.zip`は設定・認証情報を含み得る。展開は`python3 scripts/prepare-dev.py`で必要なJARだけ。設定を読んだりログへ出したりしない。
- Java 25、Gradle Wrapperを使用。`./gradlew clean build`で検証する。
- コンパイル対象：Paper 26.3 build 227、Towny 0.103.2.0、SiegeWar 3.7.0、VaultUnlocked 2.20.3。実在APIをJAR・公式ソースで確認する。
- Bukkit/Paper/Towny/Vault APIはメインスレッド。DB・HTTPは単一の有界I/Oキュー。メインスレッドからI/O結果を待たない。
- Webhook URL・トークン・HTTP例外メッセージをログに出さない。設定パース例外の詳細も出さない。
- 日次レポートは毎日12:00 Asia/Tokyo。主キーは`daily:YYYY-MM-DD`、カーソルは`next-day`。旧週次履歴・`next-week`は保持し、日次と混在させない。送信前にSQLiteへ状態を保存し、送信結果不明を無条件に再送しない。
- ワールド、プレイヤーデータ、プラグイン設定、DB、JAR（Wrapper以外）、ZIPはGit管理しない。
- `integrationJar`は開発専用。本番JARに含めない。本番に設置しない。
- 開発Composeは専用プロジェクト名・localhost公開のみ（実機接続ではMinecraft認証への外向き通信を許可）。終了時に`docker compose down`し、ボリューム削除オプションは付けない。
- テスト結果と未実施項目は`docs/verification.md`へ記録する。実プレイヤー操作と合成イベントの検証を区別する。

- 公開前は`python3 scripts/public_repo.py check`でステージ済み全ファイルを検査する。`.gitignore`だけで既存の追跡ファイルが消えると考えない。
- 実プレイヤー名・UUID・実残高・内部ホスト名・ローカルユーザーパス・生ログを公開ドキュメントへ記載しない。検証結果は匿名化する。
- 許可リスト外のファイルを公開する場合は用途と秘密情報の有無を確認して`public_repo.py`を更新する。検査失敗を無視するためにフックを無効化しない。
- 公開用コピーを作る場合は`public_repo.py export`を使用し、既存の出力先やGit履歴を上書きしない。Pushはユーザーの公開指示と対象を確認してから行う。
