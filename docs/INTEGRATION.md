# 他のアプリとのつながり

Keepに無い「他のアプリとの連携」は、**データがObsidian形式のファイルであること**で実現しています。どのアプリからも同じ `Theta Vault/Memo/` を読み書きできます。

## PC・Mac（今すぐ使える）

1. **Googleドライブ for デスクトップ**を入れると、`マイドライブ/Theta Vault/` がPCのフォルダとして見えます。
2. **Obsidian**で「フォルダを保管庫として開く」→ `Theta Vault` を選ぶ。メモ・写真・PDF・録音・手書きがそのまま表示・編集でき、編集はスマホに戻ってきます。
3. **ブラウザからスクラップ（PC・Mac）**：[Obsidian Web Clipper](https://obsidian.md/clipper)（Chrome・Edge・Firefox・Safari の拡張機能、無料）を入れ、設定の「保管庫」に `Theta Vault`、保存先フォルダに **`Memo/Clippings`** を指定します。切り抜いたページは次の同期でスマホの一覧に並びます（`source:` もそのまま使います）。
4. Android では、ブラウザの「共有」→「Theta Memoに保存」で、ページのタイトル・画像・概要・リンクがメモになります。写真・PDF・その他のファイルも「共有」で1件のメモとして保存できます。

## Theta Home Hatch（Android）

同じ署名鍵のシリーズアプリから使える入口です（権限 `jp.yaegaki.theta.permission.SHARE`）。

| 用途 | 方法 |
| --- | --- |
| メモの一覧を出す | `content://jp.yaegaki.theta.memo.theta/notes?limit=50` を query。列：`id, title, snippet, color, pinned, updated, cover`（固定 → 新しい順、アーカイブ・ゴミ箱は除く） |
| メモを開く | `Intent(MainActivity)`、extra `open` = id（パッケージ `jp.yaegaki.theta.memo`） |
| 新しいメモ | action `jp.yaegaki.theta.memo.NEW`、extra `type` = `text` / `list` / `ink` / `audio` / `photo` / `file`、任意で `android.intent.extra.TEXT` |
| シリーズの発見 | action `jp.yaegaki.theta.HATCH`（Meishi と同じ） |
| 画像のサムネイル | Vaultの `Memo/_files/<cover>` を読む（Home Hatch が Vault に対応したとき） |

Home Hatch の「記録」ドロワー（録音・Sペン）から `NEW` を呼ぶと、記録をそのままメモにできます。

## DeskHatch（Windows・Mac）

- 今：DeskHatch の付箋（sticky）とスクラップは `.md` ファイルを読み書きする作りなので、`Theta Vault/Memo/Notes/` のファイルをそのまま付箋として貼れます。
- 次：シリーズ共通仕様 §7 の「Theta Hatchプラグイン」ドロワー。共通UI（`android/app/src/main/assets/`）はブラウザでも動くように作ってあり（`Native` のブリッジだけ差し替え）、デスクトップ版（Electron）・Web版（PWA）は Meishi と同じ形で追加予定です。

## Meishi など他のシリーズアプリ

- 同じ Vault の中なので、メモから `[[Meishi/People/山田 太郎（サンプル株式会社）]]` のようにリンクできます（Obsidianでたどれます）。
- Gemini APIキーは、同じ端末の Meishi から設定画面で借りられます（キー自体はクラウドに置きません）。
