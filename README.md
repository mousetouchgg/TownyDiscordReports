# TownyDiscordReports

Paper + Towny + SiegeWar向け、日本語Discord通知・日次経済ランキングプラグイン。Java 25、Gradle Wrapper、SQLite、JUnitを使用する。DiscordSRVには依存しない。

国家・町の作成/削除、国王交代、攻城戦開始/終了・勝敗確定をEmbedで通知する。プレイヤー所持金と国家銀行残高を集計し、前日比と順位変動を毎日12:00（Asia/Tokyo）に配信する。

検証環境と実施結果は[検証記録](docs/verification.md)、実在APIと互換性は[API確認記録](docs/api-compatibility.md)を参照。

## 開発環境の準備

必要なもの：JDK 25、Python 3、Docker Engine + Compose、Git。ホスト標準のJava 11ではビルドできない。JDKを用意し`JAVA_HOME`と`PATH`を設定する。

```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
python3 scripts/prepare-dev.py --plugins /path/to/plugins.zip --download-paper
./gradlew clean build
```

`prepare-dev.py`はTowny 0.103.2.0、SiegeWar 3.7.0、VaultUnlocked 2.20.3、EssentialsX 2.22.1-dev+27-e70bdb8のJARだけを`dev/plugins/`へ取り出す。アーカイブ内の設定、DB、鍵は取り出さない。異なる既存JARを上書きしない。ファイル名の異なる構成は検証してからスクリプトと`build.gradle`を更新する。

正しいPaper JARがある場合は`--download-paper`の代わりに`--paper /path/to/paper.jar`を指定できる。`--download-paper`は公式配布の26.3 build 227を取得する。ダウンロード時はSHA-256を照合する。

成果物：`build/libs/TownyDiscordReports-1.1.1.jar`。SQLite JDBCとGsonを同梱し、Towny/Vault/SiegeWarは同梱しない。WrapperはGradle 9.2.1に固定し、配布ZIPのチェックサムを設定済み。初回ビルドにはインターネットが必要。

テストのみ：`./gradlew test`。HTML結果は`build/reports/tests/test/index.html`。

## 開発用Paper

```bash
# 初回だけ：Minecraft EULAを確認した上でdev/eula.txtをeula=trueに変更
# https://www.minecraft.net/eula
# 先にホストで依存ダウンロードを済ませる場合（通常はComposeで直接起動可能）
cd dev
"$JAVA_HOME/bin/java" -jar paper.jar --nogui
cd ..
cp build/libs/TownyDiscordReports-1.1.1.jar dev/plugins/
docker compose up -d
docker compose logs -f
```

初回ホスト実行は`eula=false`のまま行うと依存展開後に停止する。EULA設定後はComposeで起動する。開発ワールド・設定は新規生成する。提供ZIPに入っている設定をコピーしない。

ホストのUID/GIDが1000でない場合は`LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) docker compose up -d`を使う。専用プロジェクト名は`towny-discord-reports-dev`。ポートはlocalhostの25575だけに公開し、専用のbridgeネットワークを使用し、Minecraft認証のため外向き通信を許可する。本番Botは配置していない。Minecraft Java版26.3から`localhost:25575`へ接続する。

コンソール操作は`docker attach towny-discord-reports-dev-paper-1`。コマンドを入力できる。切断はCtrl-P、Ctrl-Q。Ctrl-Cでの切断は避ける。

終了はコンソールの`stop`、その後`docker compose down`。データを削除する`down -v`は不要。

## 設定

初回起動で`plugins/TownyDiscordReports/config.yml`が生成される。[コメント付きテンプレート](src/main/resources/config.yml)にすべての項目を記載した。

|キー|用途・既定値|
|---|---|
|`webhook-url`|空欄ならHTTPと日次レポート処理を停止。環境変数`TDR_WEBHOOK_URL`を優先|
|`notifications.*`|イベントごとのON/OFF|
|`ranking.enabled`|日次ランキング配信の有効化。管理用の現在順位参照は利用可能|
|`ranking.limit`|1〜50件、既定10件|
|`schedule.time/timezone`|`'12:00'` / `Asia/Tokyo`。毎日配信。旧`schedule.day`は不使用|
|`sqlite-path`|データフォルダーからの相対パス、既定`reports.db`。変更は再起動が必要|
|`retry.attempts`|429時の最大試行回数、既定3回|
|`retry.delay-seconds`|再試行の最低待機秒数、既定2秒|
|`retry.timeout-seconds`|接続・リクエストタイムアウト、既定10秒|
|`debug`|送信成功などの診断ログ、既定false|

URLは`https://discord.com/api/webhooks/ID/TOKEN`形式だけを許可する。リダイレクトは追わない。秘密情報やHTTP例外の本文をログへ出さない。WebhookはDiscordのテストサーバーで発行し、ファイルをGit管理しない。環境変数はComposeに明示的に渡さない限りコンテナへ継承されない。開発Composeは外向き通信を許可するが、Webhookは未設定のため通知しない。

`/tdr reload`は検証後に設定を切り替える。不正な設定では稼働中の設定を保持する。日次処理中のreloadは拒否する。保存先は変更せず、SQLite移行は停止してから行う。

## 管理コマンド

専用Permission：`townydiscordreports.admin`（既定OP）。全コマンドをコンソールでも実行できる。コンソールでは先頭`/`を省略する。

|コマンド|動作|
|---|---|
|`/tdr status`|Towny/SiegeWar/Vault/SQLite/Webhook状態、次回日時、配信状態別件数|
|`/tdr reload`|設定を検証して再読み込み|
|`/tdr ranking player`|現在のプレイヤー所持金順位|
|`/tdr ranking nation`|現在の国家銀行順位|
|`/tdr send daily`|予定時刻を過ぎた最古の未作成対象日を1日処理。自動配信と同じ識別子で重複防止|
|`/tdr testdiscord`|ランキング履歴と無関係のテストEmbedを1件送信|

旧`/tdr send weekly`は日次配信の互換エイリアスとして残す。

手動送信は「強制再投稿」ではない。送信済み日の内容を再集計・再投稿しない。`FAILED`/`UNCERTAIN`も自動的には再送しない。現在のランキング参照コマンドはSQLite履歴を変更しない。

## ランキングの対象・制限

プレイヤーは`Bukkit.getOfflinePlayers()`と`Bukkit.getOnlinePlayers()`をUUIDで統合し、オンラインまたはプレイ履歴があり、Vaultの`hasAccount(OfflinePlayer)`がtrueのもの。初回ログイン中も含める。UUIDで識別し、取得時の最新の既知名を表示する。Vaultに全口座列挙APIはなく、Essentialsユーザーファイルだけが残る口座、NPC、削除済みプレイヤーデータは網羅できない。オフライン口座の正確性は利用プロバイダーに依存する。個別プレイヤーデータやEssentials設定を直接読み書きしない。

国家はTowny APIで取得できる全国家。国家UUIDで識別する。Towny経済無効時は空のランキングとし、サーバーを停止させない。取得例外、NaN、Infinityは除外し、件数のみログへ出す。有限の負残高は借金として扱う。

残高と前日差額の表示は小数点以下2桁に四捨五入する（HALF_UP）。Discordとゲーム内コマンドで共通。順位計算とSQLite保存では丸めず、取得時の精度を保持する。既存の送信済み・送信待ちJSONは書き換えない。

残高降順、同額はUUID文字列昇順で連番順位を付ける（同順位方式ではない）。全対象の順位・残高を保存し、表示だけ上位N件に絞る。前日差額はUUIDで照合し、順位変動は「前日順位 − 当日順位」。前日レポートなしは「前日データなし」、前日対象に同じUUIDがなければ「前日対象外」。

Bukkit/Towny/Vaultの取得はメインスレッドで行い、取得済みの不変データだけをI/Oスレッドへ渡す。大規模サーバーでは全オフラインプレイヤー走査が1tickを長くする可能性がある。現バージョンは分割集計を実装していないため、実データ規模で計測してから本番採用する。

## 日次履歴と停止時の補完

1分間隔で予定時刻を確認する。初回導入または旧週次版からの切替時は**次の12:00**から開始し、それ以前の日次分は作らない。時刻・タイムゾーンは設定に従う。初回以降はSQLiteの`next-day`を永続化し、停止中の未作成日を古い順に1分につき1日補完する。手動コマンドも予定時刻を過ぎた未配信分だけを1日処理し、時刻前に当日分を先行送信しない。

停止中の過去残高は復元できない。補完レポートは**再起動後に取得した残高**を使い、対象日と実集計日時を表示する。比較対象は直前の配信回ではなく、前のカレンダー日の日次履歴。前日の履歴がなければ「前日データなし」。

`reports`の主キーは`daily:YYYY-MM-DD`、`balances`は対象キー・種別・UUID、`deliveries`は対象キー・Embed部分番号。既存スキーマの列名`week`は互換性のため維持する。旧週次の日付キーと`next-week`は変更・削除せず、日次では使用しない。同じ日付の旧週次履歴があっても日次履歴と衝突しない。旧週次のPENDINGも自動再送しないため、残件は運営者が個別に確認する。

予定日時と実集計日時、全順位、名前・残高、変更しない送信JSON、配信状態をトランザクションで保存する。残高は十進文字列として保存し、マイグレーションは`PRAGMA user_version`で管理する。日次対応は既存v1スキーマの範囲で保存でき、スキーマ破壊や過去データの書換えを必要としない。新しい未対応スキーマを検出した場合は書き込まない。

配信状態：`PENDING → SENDING → SENT`。送信前に`SENDING`を永続化する。429は`Retry-After`ヘッダーと`retry_after`本文の大きい方を待って再試行する。300秒超は待機せず`FAILED`。その他の4xxも`FAILED`。5xx、タイムアウト、通信切断は受理済みの可能性があるため`UNCERTAIN`にして自動再送しない。再起動時に残る`SENDING`も`UNCERTAIN`へ移す。

Discord Webhookには冪等キーがなく、HTTP応答消失とDiscord側受理の間を原子的に確定できない。そのため厳密なexactly-onceは保証できない。本実装は曖昧な送信の自動重複を避けるため、運営者の確認を必要とする。

未送信の日次`PENDING`部分は再起動後に続行する。通常イベントはメモリーの有界キューだけで、サーバー停止・キュー満杯時に消失し得る。イベント通知履歴の永続化は対象外。

## 配信失敗の復旧

`/tdr status`とログの対象日・部分番号を確認する。Discordの実投稿と照合し、サーバー停止・DBバックアップ後に対象部分だけ変更する。SQLiteにWebhook URLは保存しないが、プレイヤー名・残高は含むためアクセスを制限する。

```sql
SELECT week, part, state FROM deliveries ORDER BY week, part;
-- 実投稿を確認できた部分だけ
UPDATE deliveries SET state='SENT' WHERE week='daily:YYYY-MM-DD' AND part=0 AND state='UNCERTAIN';
-- 投稿されていないことを確認して再送を承認した部分だけ
UPDATE deliveries SET state='PENDING' WHERE week='daily:YYYY-MM-DD' AND part=0 AND state IN ('FAILED','UNCERTAIN');
```

再起動し、次の自動確認または`/tdr send daily`でPENDINGを処理する。全履歴を削除して再送させない。

## インストール・本番導入

本リポジトリから本番へ自動デプロイはしない。運営者が以下を実施する。

1. [未実施の実機テスト](docs/verification.md)をテストサーバーとテストWebhookで完了する。
2. 対象サーバーのPaper・Java・Towny・SiegeWar・Vault・EssentialsXが検証版と一致することを確認する。
3. サーバーを停止し、既存JAR・設定・SQLiteとワールドをバックアップする。
4. **製品JARのみ**を`plugins/`へ配置する。`TDRDevelopmentProbe`は配置しない。
5. Java 25起動オプションに`--enable-native-access=ALL-UNNAMED`を追加して起動する。
6. 通知先は空欄のまま`tdr status`で連携を確認し、対象DiscordのWebhookを設定して`tdr reload`する。
7. 運営者の判断で`tdr testdiscord`、`tdr send daily`を実施し、DiscordとSQLiteの状態を照合する。初回配信は次の設定時刻から開始する。

## バックアップ・ロールバック

設定はWebhookを含むため、権限を制限した別保管先へバックアップする。SQLiteはサーバー停止後に`reports.db`と、残っている場合は`reports.db-wal`・`reports.db-shm`をセットで保存する。稼働中はSQLiteのbackup API/`.backup`を使用し、DB本体だけの単純コピーは避ける。

ロールバックはサーバー停止後、新JARを退避し旧JAR・対応スキーマのDBと設定を戻す。週次版へ戻す際は日次対応前のDBバックアップを使用し、週次版が日次のPENDINGを処理しないようにする。バックアップ以降にDiscordへ投稿された部分を照合し、古いDBに戻したことで既投稿がPENDINGにならないよう調整する。初回導入を取り消すだけならJARを外す。ワールドと他プラグインのJARを変更しない。

## トラブルシューティング

- Javaエラー：`java -version`と`JAVA_HOME`を確認する。Java 25必須。
- コンパイル時にTowny等がない：`prepare-dev.py`で必要なJARを用意する。
- Docker permission denied：Docker利用権限を設定したシェルで実行する。本番Dockerには接続しない。
- Webhook未設定：空欄や環境変数を確認。URLをログへ貼らない。
- SQLite=false：保存先権限、空き容量、対応スキーマ、他プロセスによるロックを確認する。
- Vault未取得：Vaultと実際の経済プラグインが有効か確認する。Vault単体は残高を提供しない。
- 国家の対象なし：Towny経済が有効か、国家が存在するか確認する。
- `FAILED/UNCERTAIN`：上述の手順で実投稿と照合する。手動コマンドだけでは再投稿しない。
- 接続できない：`docker compose ps`に`127.0.0.1:25575->25565/tcp`があるか確認する。旧構成の内部ネットワークが残る場合は`docker compose down`後に`docker compose up -d`を行う。

## Git管理

ソース・Gradle設定・Wrapper・テスト・ドキュメント・秘密を含まないテンプレートだけを管理する。`.gitignore`で開発データ、依存JAR/ZIP、DB、鍵、IDE設定、バックアップを除外する。

```bash
git config core.hooksPath .githooks
git add .
python3 scripts/public_repo.py check
git diff --cached --check
git diff --cached
```

公開前の手順、ローカルデータが混在した環境からの安全な書き出し、Git履歴とメールアドレスの確認は[公開手順](docs/publication.md)を参照。

## ライセンス

プロジェクト自身のライセンスは未設定。第三者コンポーネントは[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)を参照。
