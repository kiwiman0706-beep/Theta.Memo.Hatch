# Theta Memo Hatch

Google Keep のような付箋の壁のメモアプリ。ただしデータは **Obsidian 形式のファイル（Theta Vault）** で Google ドライブに置くので、PC・Mac・他のアプリからも読み書きでき、リッチテキスト・表・あらゆるファイルの添付ができます。Theta Hatch シリーズ（[Theta Meishi Hatch](https://github.com/kiwiman0706-beep/MeishiApp) と同じ Vault・同じ約束ごと）。

| Keep のよいところ（そのまま） | Keep に足りなかったもの（追加） |
| --- | --- |
| 一面に付箋を貼ったような一覧、色・固定・ラベル・アーカイブ | 保存は Obsidian 形式（Markdown＋添付）→ 他のアプリと連携 |
| テキスト・チェックリスト | リッチテキスト（見出し・太字・ハイライト…）・表 |
| 手書き（Sペン対応） | 写真・PDF・動画・あらゆるファイルを添付し、一覧にサムネイル |
| 音声メモ（音声認識つき、音声ファイルも残す） | ブラウザからのスクラップ（Android の共有、PC・Mac は Obsidian Web Clipper） |
| ウィジェット（1つのメモ・メモ一覧） | PC・Mac・Android で同期（Google ドライブ） |
| | Google Keep から Takeout で取り込み |
| | GTD（受信箱・整理・次にやる・待ち・予定・プロジェクト・週次レビュー）とアイゼンハワーマトリクス |
| | 入れ子（子メモ・アウトライン）とマインドマップ（[docs/GTD.md](docs/GTD.md)） |

## 使い始める

1. APK を入れる（[Releases](https://github.com/kiwiman0706-beep/Theta.Memo.Hatch/releases)）。
2. 同期：メニュー →「Google Driveと同期を始める」。最初に Google Cloud で **Android 用 OAuth クライアントを1つ追加**します（Meishi と同じプロジェクト。パッケージ名とSHA-1はアプリの「初期設定ガイド」でコピーできます）。
3. Keep のメモ：メニュー →「Google Keepから取り込む」（Takeout の ZIP を選ぶ）。
4. PC・Mac：Google ドライブ for デスクトップ＋Obsidian で `Theta Vault` を開く。ブラウザのスクラップは Obsidian Web Clipper の保存先を `Memo/Clippings` に（[docs/INTEGRATION.md](docs/INTEGRATION.md)）。

## フォルダ

| 場所 | 中身 |
| --- | --- |
| `android/` | Android アプリ（Java＋WebView）。`app/src/main/assets/` が共通 UI（HTML/CSS/JS、Windows/Mac/Web 版でも使う予定） |
| `android/tests/` | 共通 UI のブラウザテスト・Markdown 変換のテスト |
| `docs/THETA_MEMO_VAULT.md` | データの形（frontmatter・本文・添付・同期・Keep の対応表） |
| `docs/INTEGRATION.md` | Obsidian・Web Clipper・Home Hatch・DeskHatch との連携 |
| `docs/TESTING.md` | 自動テストと、実機で未確認のこと |
| `docs/RELEASING.md` | リリースの手順（シリーズ共通の署名鍵） |

## ビルド

```sh
cd android
./gradlew testDebugUnitTest assembleDebug     # テストとお試し用APK（app/build/outputs/apk/debug/）
node tests/md.test.cjs
NODE_PATH=$(npm root -g) node tests/ui.test.cjs   # 要 playwright
```

Android SDK（platform 35 / build-tools 35.0.0）と JDK 17 以上が必要です（Android Studio に同梱）。
