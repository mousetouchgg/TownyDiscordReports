# 公開リポジトリの管理

公開先：[mousetouchgg/TownyDiscordReports](https://github.com/mousetouchgg/TownyDiscordReports)。
本書の手順はGitHubへの自動Pushを行わない。

## 公開するもの

- Javaソース・単体テスト・開発専用統合プローブのソース
- Gradle設定、Gradle Wrapper、plugin.yml、SQLiteスキーマ
- Webhookが空欄のconfig.ymlテンプレート
- Compose、開発準備・公開検査スクリプト
- 匿名化済みドキュメント、第三者ライセンス情報

第三者プラグインJAR、Paper/Minecraft/JDK、ビルド済み製品JARはソースリポジトリに含めない。
開発者は必要な依存JARを自分の環境の`dev/plugins/`へ用意する。

## 公開しないもの

本番由来のZIP、プラグイン設定、Webhook URL、Botトークン、SSH鍵、環境変数ファイル、DB、ワールド、プレイヤーデータ、ログ、バックアップ、IDE設定、ローカルのツールキャッシュ。
`.gitignore`は新しい未追跡ファイルを除外する設定。すでに追跡されたファイルや過去コミットの内容は消さない。

## ローカルデータが混在した環境からの書き出し

```bash
python3 scripts/public_repo.py export \
  --output public-repo \
  --remote https://github.com/mousetouchgg/TownyDiscordReports.git
cd public-repo
```

許可リストに合うソースだけをコピーし、内容を検査してから、新しい`main`ブランチと`origin`を持つGitリポジトリを作る。ファイルはステージ済み、コミット・Pushは未実行。既存の出力先は上書きしない。実サーバーや元のZIPは変更しない。
`public-repo/`は元の作業フォルダー側でGit除外する。書き出し先の`.gitignore`は、そのリポジトリ内部の設定として機能する。
通常のGit cloneから開発する場合は、書き出しを繰り返さず、そのチェックアウトで管理する。

## 最初のコミット前

```bash
git remote -v
git status --short
git config core.hooksPath .githooks
python3 scripts/public_repo.py check
git diff --cached --check
git diff --cached --stat
git diff --cached
```

Gitの作者名・メールアドレスもコミットに保存される。`git var GIT_AUTHOR_IDENT`で本人が確認し、必要に応じてGitHubのメール設定に表示されるnoreplyアドレスを、このリポジトリの`git config user.email`へ設定する。トークンをremote URLに含めない。

確認後、管理者自身がコミットとPushを行う。

```bash
git commit -m "Initial public source for TownyDiscordReports"
git push -u origin main
```

既存のリモート履歴がある場合は先に確認・統合する。force pushや履歴の無断書換えをしない。
プロジェクトのライセンスは未選択。選ぶ場合は著作権者が内容を確認してから追加する。第三者ライセンス情報とは区別する。

## 継続的なチェック

pre-commitフックは`python3 scripts/public_repo.py check`を実行する。検査対象は作業ツリーではなく、**Gitインデックスに保存された全ファイル**。コミット直前の変更差分だけでなく、すでに追跡されたファイルも含める。

- 公開許可リスト外のファイル、シンボリックリンク、想定外のバイナリを拒否
- Gradle Wrapper JARのチェックサムを固定
- 既知のWebhook・アクセストークン・秘密鍵パターンを検出
- 公開テンプレートのWebhookが空欄か確認
- ローカルユーザーパスを検出
- 検査失敗時はパスと種類だけを表示し、検出値や行全体は表示しない

検査は既知パターンに基づく補助であり、あらゆる秘密情報・個人情報を判別できるわけではない。レビューで内容も確認する。新しい公開ファイルの種類を追加する場合は`public_repo.py`の許可リストも確認する。

秘密情報が過去にコミットされた場合は、`.gitignore`の追加だけで解決したと扱わない。必要な認証情報の失効・再発行と、影響する履歴の確認を行う。履歴の書換えは管理者の承認後に行う。

## 検査ツールのテスト

```bash
python3 -m unittest discover -s scripts -p 'test_public_repo.py'
```

開発サーバーや実Webhookを使わず、一時ディレクトリと合成した検査用データで確認する。
