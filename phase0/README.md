# Phase 0: Rundeck 5.x 失敗再現

Rundeck **5.9.0** + **モック Webhook** で現行プラグインの挙動を確認します。  
Teams Webhook URL は不要です（`mock-webhook` コンテナが POST を受信してログ出力します）。

## 前提

- Docker / Docker Compose
- ポート `4440`, `4443`, `18080` が空いていること

## 実行

```bash
cd phase0
chmod +x run-phase0.sh
./run-phase0.sh
```

## 確認内容

1. プラグイン配置パス（`/home/rundeck/libext` vs `/var/lib/rundeck/libext`）
2. プラグインが叩く API（`https://localhost:4443/api/31`）がコンテナ内で応答するか
3. ジョブ成功時に通知プラグインが動き、モック Webhook に POST されるか
4. Rundeck ログのエラー

結果は `phase0-report.txt` に出力されます。

## 停止

```bash
docker compose down
```

## 本番 Teams 検証について

Phase 0 ではモック Webhook で代替検証しました。実 Teams Workflows Webhook での動作も確認済みです。

## 調査結果

`FINDINGS.md` に Phase 0 の原因分析をまとめています（2026-06-29 時点）。
