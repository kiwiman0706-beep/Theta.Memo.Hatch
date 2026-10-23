# リリースの手順

1. `android/app/build.gradle` の `versionName`（と `versionCode` を1つ上げる）を変え、`docs/releases/v<版>.md` を書く。
2. 初回だけ、リポジトリの Settings → Secrets and variables → Actions に、**Theta Meishi Hatch と同じ署名鍵**を登録：
   - `ANDROID_KEYSTORE_B64`：`base64 -w0 meishiclaude-release.jks`（Macは `base64 -i meishiclaude-release.jks`）の出力
   - `ANDROID_KEYSTORE_PASSWORD`：鍵のパスワード
   - `ANDROID_KEY_ALIAS`：別名が `meishiclaude` 以外のときだけ
3. Actions →「Release」→「Run workflow」。テストのあと署名済みAPKを作り、Release `v<版>` を公開します（再実行でAPKを差し替え）。
4. アプリは12時間ごとに最新Releaseを見て、Wi-Fiで取得し、アプリ内で更新します。

同じ鍵にする理由：Meishi とのAPIキーの貸し借り（`SeriesProvider`）、Home Hatch からのメモ一覧の読み取りが「同じ署名のアプリだけ」に許されるため。Google CloudのAndroid OAuthクライアントのSHA-1も Meishi と同じになります。

手元でビルドする場合：`ANDROID_SDK_ROOT=… SIGNING_FILE=… SIGNING_PASS_FILE=… android/build.sh`
