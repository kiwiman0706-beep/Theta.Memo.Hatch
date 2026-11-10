# リリースの手順

1. `android/app/build.gradle` の `versionName` と `versionCode`（1 つ上げる）を変え、`docs/releases/v<版>.md` を書く。
2. 署名はシリーズ共通の鍵です（Theta.Hatch.Series の `secrets/README.md`）。リポジトリの Secret は 4 つ：`ANDROID_KEYSTORE_B64`・`ANDROID_KEYSTORE_PASSWORD`・`ANDROID_KEY_ALIAS`・`ANDROID_KEY_PASSWORD`。Theta.Hatch.Series の `tools/sync-secrets` でまとめて配ります。別名はワークフローにもスクリプトにも書きません。
3. `main` に push すると、版がまだ出ていなければ Actions がテスト → `ThetaMemoHatch-<版>.apk`（共通鍵で署名）と `SHA256SUMS-<版>.txt` を作り、タグ `v<版>` と Release を自動で公開します（spec §6.1 の条件：パッケージ名・版・署名の SHA-256 を確かめる。版に `-` があれば試験版）。手で実行（Actions →「Release」→「Run workflow」）すると、同じ版の APK を差し替えます。
4. 端末への更新は Theta Home Hatch・KidsHome Hatch（更新のハブ）が行います。このアプリはハブに頼むだけで、ハブが無ければ Releases ページを開きます。

手元でビルドする場合：`SIGNING_FILE=… ANDROID_KEYSTORE_PASSWORD=… ANDROID_KEY_ALIAS=… ANDROID_KEY_PASSWORD=… android/build.sh`
