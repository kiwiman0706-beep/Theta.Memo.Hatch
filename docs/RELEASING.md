# リリースの手順

1. `android/app/build.gradle` の `versionName` と `versionCode`（1 つ上げる）を変え、`docs/releases/v<版>.md` を書く。
2. 署名はシリーズ共通の鍵です（Theta.Hatch.Series の `secrets/README.md`）。リポジトリの Secret は 4 つ：`ANDROID_KEYSTORE_B64`・`ANDROID_KEYSTORE_PASSWORD`・`ANDROID_KEY_ALIAS`・`ANDROID_KEY_PASSWORD`。Theta.Hatch.Series の `tools/sync-secrets` でまとめて配ります。別名はワークフローにもスクリプトにも書きません。
3. Actions →「Release」→「Run workflow」。テストのあと `ThetaMemoHatch-<版>.apk`（共通鍵で署名、SHA-256 を確かめる）を作り、Release `v<版>` を公開します（再実行で APK を差し替え）。
4. 端末への更新は Theta Home Hatch・KidsHome Hatch（更新のハブ）が行います。このアプリはハブに頼むだけで、ハブが無ければ Releases ページを開きます。

手元でビルドする場合：`SIGNING_FILE=… ANDROID_KEYSTORE_PASSWORD=… ANDROID_KEY_ALIAS=… ANDROID_KEY_PASSWORD=… android/build.sh`
