# Phase 0 調査結果（Rundeck 5.9.0）

実施日: 2026-06-29（更新: 2026-06-30）  
環境: `rundeck/rundeck:5.9.0` (Docker)、macOS arm64（イメージは linux/amd64 エミュレーション）  
Teams Webhook: **実 Teams Workflows Webhook で動作確認済み**（2026-06-30。Phase 0 では mock-webhook でも検証）

---

## 結論（原因・優先度順）

### 1. ジョブ定義のプラグイン `type` 指定が Rundeck 5.x と不一致（確定・解決）

ジョブ YAML の `notification.*.plugin.type` には、**表示名（`title`）ではなく Groovy ファイル名（拡張子なし）** を指定する必要がある。

| 指定値 | import 結果 |
|--------|-------------|
| `Microsoft Teams notification with log output`（title） | **失敗** — plugin not found |
| `MicrosoftTeamsNotificationWithLog`（ファイル名） | **成功** |
| `Phase0 Minimal Notification`（title） | **失敗** |
| `MinimalNotificationPlugin`（ファイル名） | **成功** |

Rundeck 3.x 時代のジョブ定義や README の例が title ベースの場合、5.x 移行時に **ジョブ定義の更新が必須**。

`/api/52/plugin/list` では `title` と `name`（= ファイル名）の両方が返るが、**ジョブ import のバリデーションは `name` で解決**する。

### 2. Rundeck API エンドポイント不一致（確定）

現行プラグインはログ取得に以下を固定使用している。

```
https://localhost:4443/api/31/execution/{id}/output
```

Rundeck 5.9 Docker コンテナ内での確認結果:

| エンドポイント | HTTP ステータス | 意味 |
|----------------|-----------------|------|
| `https://localhost:4443/api` | **000**（接続不可） | HTTPS 4443 で API が応答しない |
| `http://localhost:4440/api` | **200** | HTTP 4440 で API が応答する |

`include_outputlog=true` でジョブ成功通知を送ると、`getRundeckLog` 内の curl が空応答となり、  
`JsonSlurper.parseText` で `IllegalArgumentException: Text must not be null or empty` が発生する（**通知全体が失敗**）。

`include_outputlog=false` ではログ API を呼ばないため、**mock Webhook への Adaptive Card POST は成功**（2026-06-30 確認）。

`include_outputlog=true` 時の対策として、プラグインは `framework.properties` の `framework.server.url` から API URL を自動導出し、API バージョンも Rundeck API から自動検出する（環境変数は使用しない）。

### 3. API バージョンの乖離（確度中）

- Rundeck 5.9 の API バージョン: **52**
- プラグイン固定値: **31**

エンドポイントを `http://localhost:4440/api/52` に修正したうえで output 形式の互換を要確認。

### 4. Groovy プラグインのロード（確定・問題なし）

**Groovy プラグインは Rundeck 5.9 で正常にロードされる。** 以前の「未登録」は `type` 指定ミスによる誤判定だった。

確認方法:

- `/api/52/plugin/list` に `MicrosoftTeamsNotificationWithLog` / `MinimalNotificationPlugin` が登録される
- `type: MicrosoftTeamsNotificationWithLog` でジョブ import が成功する
- 最小プラグイン `MinimalNotificationPlugin` でも同様

`libext/cache/` に Groovy 用エントリがないのは **正常**（Groovy は Spring Bean、JAR のみ cache）。

### 5. アセットパス（確度中・nolog 時は動作確認済み）

プラグインはテンプレート・アイコンを `/var/lib/rundeck/libext/MicrosoftTeamsNotificationWithLog.d` から読む。  
Phase 0 ではボリュームマウント済みで、`include_outputlog=false` の Adaptive Card 送信は成功。

---

## Phase 0 で実施できたこと / できなかったこと

| 項目 | 結果 |
|------|------|
| Rundeck 5.9 起動 | 成功 |
| Groovy プラグインロード | **成功**（`type` をファイル名に修正後） |
| ジョブ import（通知付き） | **成功**（`MicrosoftTeamsNotificationWithLog`） |
| ジョブ実行 → mock Webhook POST（nolog） | **成功**（Adaptive Card JSON 受信確認） |
| ジョブ実行 → ログ取得 → Webhook（log あり） | **失敗**（API 4443/31 不一致） |
| API 接続性（4443 vs 4440） | 上記のとおり確認 |
| 実 Teams Webhook 確認 | **成功**（Workflows Webhook URL、ユーザー確認済み） |

---

## 再現手順

```bash
cd phase0
docker compose down && docker compose up -d
# healthy になるまで待機（数分）

# プラグイン type の切り分け
./test-plugin-type.sh

# 本番相当の検証（type は MicrosoftTeamsNotificationWithLog を使用）
./run-phase0.sh
```

補助スクリプト:

- `test-groovy-load.sh` — Groovy ロード診断（`type` はファイル名を使うこと）
- `test-plugin-type.sh` — title vs ファイル名の A/B テスト

停止:

```bash
docker compose -f phase0/docker-compose.yml down
```

---

## Phase 1（実装）への示唆

Grilling 合意どおり、以下が Phase 0 で裏付けられた対応:

1. `framework.properties` から API URL 自動導出 + API バージョン自動検出
2. ログ取得失敗時 nolog フォールバック、Teams 送信失敗時 `false` 返却
3. **ドキュメントにジョブ `type` の変更を明記**（`MicrosoftTeamsNotificationWithLog`）
4. 既存ジョブの移行手順（title → ファイル名）を README に追記
5. ~~実 Teams Webhook は Webhook URL 取得後に別途検証~~ → **完了**

Phase 0 で Groovy ロード自体は問題ないため、**Phase 1 実装に進める条件はほぼ充足**（実 Teams 検証を除く）。

---

## 未解決・次の調査

- [x] Groovy プラグインがロードされない直接原因 → **`type` 指定ミス**（title ではなくファイル名）
- [ ] API v52 の output 形式が v31 と同一か（エンドポイント修正後）
- [ ] `include_outputlog=true` での end-to-end（API 環境変数化後）
- [x] 実 Teams Workflows Webhook への POST（ユーザー確認済み）
