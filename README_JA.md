# api-private-router

api-private-router は、管理画面、ユーザーセンター、決済フロー、モデルゲートウェイを含むプラットフォームです。

## 技術スタック

- バックエンド: Spring Boot, PostgreSQL, Redis
- フロントエンド: Vue 3, TypeScript, Vite, Pinia
- デプロイ: Docker Compose, systemd, Java サービス

## ディレクトリ構成

- `java-backend/`: Java バックエンド、テスト、DB マイグレーション
- `frontend/`: 管理画面とユーザー向け Web アプリ
- `deploy/`: Docker、systemd、環境設定テンプレート
- `docs/`: 決済および統合メモ
- `tools/`: リポジトリ検証と保守スクリプト

## クイックスタート

デプロイ:
- Docker: `deploy/README.md`
- Java バックエンド: `java-backend/README.md`

ローカル検証:

```bash
python tools/check_java_default_runtime.py
make test
make build
```

`make test` では Java テスト、フロントエンドの lint、typecheck、主要 UI テストをまとめて実行します。

## 実行メモ

- 対応データベースは PostgreSQL です。MySQL 8 への単純な置き換えは想定していません。
- Redis はセッション、キャッシュ、非同期処理の調整に利用します。
- 管理系、決系、決済、ユーザーセンター、ゲートウェイ API は Java バックエンドが担当します。

## 参照ドキュメント

- `deploy/README.md`
- `java-backend/README.md`
- `docs/PAYMENT.md`
- `docs/ADMIN_PAYMENT_INTEGRATION_API.md`
