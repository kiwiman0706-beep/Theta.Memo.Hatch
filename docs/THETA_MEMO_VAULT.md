# Theta Memo Hatch のデータの形（Theta Vault）

Theta Hatch シリーズ共通の [Theta Vault 仕様](https://github.com/kiwiman0706-beep/Theta.Hatch.Series/blob/HEAD/spec/THETA_VAULT_SPEC.md) に従います。**Obsidianでそのまま開けるフォルダ**が正本で、アプリはそれを読み書きするビューア兼エディタです。

## フォルダ

```
Theta Vault/                      ← NAS の選んだフォルダの中（正本）と、Googleドライブのマイドライブ直下（どちらか・両方）
├── .theta/
│   ├── vault.json                ← {"spec":1,"apps":["meishi","memo",…]}（"memo" を追記）
│   └── trash/                    ← 完全に削除したメモはここへ移動（戻せる）
└── Memo/
    ├── Notes/                    ← ふつうのメモ（1件 = 1つの .md）
    ├── Archive/                  ← アーカイブしたメモ
    ├── Trash/                    ← アプリのゴミ箱（30日後に .theta/trash へ）
    ├── Clippings/                ← Obsidian Web Clipper の保存先（PC・Macのスクラップ）
    ├── メモ.base                 ← Obsidian Bases の一覧（カード・表・GTD）。無ければアプリが置く
    └── _files/                   ← 添付（写真・PDF・録音・手書き・あらゆるファイル）
```

- Notes / Archive / Trash の間の移動はアプリが行います（アーカイブ・ゴミ箱の状態に合わせる）。
- **Clippings や、自分で作ったサブフォルダに置いたメモは動かしません**（idで追跡）。ただしアーカイブ・削除したときは Archive / Trash へ移します。
- Obsidianで `Memo/Notes/` や `Memo/Clippings/` に新しいノートを書くと、次の同期でメモとして取り込み、`id` などを書き足します。

## 1件のMarkdown

```markdown
---
theta: memo/note@1
rev: 3f2a9c1e0b7a          ← アプリが書いた時点の指紋（Obsidianでの編集を見分ける）
id: 2b6f3c1e-…
title: 買い物
color: yellow              ← Keepの色（red orange yellow green teal blue darkblue purple pink brown gray、無色は省略）
pinned: true               ← 固定（省略時 false）
archived: true             ← アーカイブ（省略時 false）
trashed: 2026-10-23T…      ← ゴミ箱に入れた日時（ゴミ箱のときだけ）
gtd: next                  ← GTDのリスト（inbox next waiting scheduled project someday reference done。ふつうのメモは省略）
important: true            ← 重要（アイゼンハワーマトリクス）
urgent: true               ← 緊急
due: 2026-11-01            ← 期限
contexts: ['@PC']          ← 状況
waiting_for: 山田さん       ← 待ちの相手
done: 2026-10-30T…         ← 完了した日時
parent: 5a1c…              ← 親メモのid（入れ子）
up: "[[親のファイル名]]"     ← Obsidian用の親へのリンク（同期が書く。Obsidianでこれだけ書いても親子になる）
view: mindmap              ← いつもマインドマップで開く（cards ならカード DB、sheet なら表計算、slides ならスライドで開く）
map_links:                 ← マインドマップの枝同士の線（枝のブロックid -> 枝 | 説明）
- m1k2 -> m9x0 | 理由
map_groups:                ← 枝をまとめる ｝ とコメント（最初の枝 .. 最後の枝 | コメント）
- m1k2 .. m3a4 | まとめ
tags:                      ← Keepのラベル（Obsidianのタグとしても使える。空白は _ に）
- 家
source: https://…          ← 切り抜いたページ（Obsidian Web Clipper と同じ項目名）
import_key: keep:…         ← Google Keepから取り込んだ元（二重取り込み防止）
created: 2026-10-23T10:00:00.000+09:00
updated: 2026-10-23T10:05:00.000+09:00
---
本文はふつうのMarkdown（リッチテキスト、見出し、太字、==ハイライト==、表…）

- [ ] 牛乳                 ← チェックリスト（Keepのリスト）
- [x] パン
- [ ] 見積書 #重要 #緊急 📅 2026-11-01   ← 行ごとの重要・緊急・期限（Obsidian Tasks と同じ書き方。docs/GTD.md）

| 品 | 数 |
| --- | ---: |
| 卵 | 10 |

![[Memo/_files/3f2a….jpg]]                 ← 写真
![[Memo/_files/9c1e….ink.png]]             ← 手書き（白地のPNG。筆跡は同名の .ink.json）
![[Memo/_files/7d0c….m4a]]                 ← 録音
> 書き起こした文字（録音・手書きのすぐ下の引用）
![[Memo/_files/5e8b….pdf|見積書.pdf]]       ← PDFやその他のファイル（| の後ろは元のファイル名）
```

### 決まりごと

| 部分 | 内容 |
| --- | --- |
| 本文 | **全部が利用者のもの**。Meishiのような「アプリが生成する区画」はありません。アプリで編集した部分はMarkdownに書き直します（エディタで触っていない段落はそのまま） |
| 添付 | `Memo/_files/` にアプリが付けた英数字の名前（中身は変えない。差し替えは新しい名前）。手書きを直すと新しいPNG・JSONになり、古いものは使われなくなった時点で端末から消えます |
| 書き起こし | 録音・手書きの埋め込みの**直後の `>` 行**。Obsidianで直してもよい |
| 知らない項目 | frontmatter の他の項目（Web Clipper の `author` `description` 等）はそのまま保持 |
| タイトル | `title:` があればそれ。無ければファイル名（Obsidianで作ったノート） |
| ファイル名 | タイトル（無ければ1行目、どちらも無ければ「メモ 日時」）。タイトルを変えると改名。同名は「 (2)」 |
| 端末だけの情報 | 同期の記録（`vault-sync.json`）、ウィジェットの設定、APIキー、サムネイルは Vault に入れません |

## 同期

Theta Meishi Hatch と同じ規則（`VaultSync.java`）。

- 変更の検出：Vault側はファイルのmd5、端末側は内容のハッシュ。
- 片側だけ変わった → その側を採用。**両側で変わった** → 色・固定・ラベル等は新しい方（ラベルは和集合）。本文が食い違うときは新しい方を残し、**もう一方を「（競合 日時）」付きの別メモとして残す**（どちらの端末で書いた文も失わない）。
- 編集は削除に勝つ。削除は `.theta/trash/` へ移動。一度に多数（max(5, 30%)超）が消えて見えるときは端末側を削除しない。
- いつ：アプリを開いたとき・戻ったとき（5分以上たっていれば）・離れたとき、1時間ごと（バックグラウンド）、メニューの「今すぐ同期」。

## NAS と Google ドライブ（両方使うとき）

- **NAS が正本**です。同期は NAS → Google ドライブの順。ドライブ側で変わったもの（PC の Obsidian、Meishi など）があれば、同じ回に NAS へもう一度同期して揃えます。
- 端末の記録は Vault ごと（`vault-sync-nas.json`・`vault-sync.json`）。削除の印（墓標）は、両方の Vault に伝わるまで残します（どちらで消しても、もう一方から戻ってこない）。
- NAS は Synology の File Station API（Tailscale 経由）。2 段階認証（Secure SignIn）はコードを 1 回入れれば、デバイストークンで次から不要。自己署名の証明書は指紋を確かめて信頼します。NAS の変更の検出は、ファイルの大きさと更新時刻です。
- Obsidian でサブフォルダへ移したノートは、NAS ではファイル名で探して追いかけます。

## Google Keep（Takeout）の対応表

| Keep | Theta Memo |
| --- | --- |
| title / textContent（太字などがあれば textContentHtml） | title / 本文 |
| listContent（isChecked） | `- [ ]` / `- [x]`（未チェックが上） |
| color | color（CERULEAN → darkblue、DEFAULT → 無色） |
| isPinned / isArchived / isTrashed | pinned / archived / trashed（ゴミ箱は既定で取り込まない） |
| labels | tags（空白は `_`） |
| attachments（写真・手書き・録音） | `_files/` に複製して埋め込み（画像は上、録音はその下） |
| annotations（リンク） | source（最初の1つ）＋本文末尾のリンク一覧 |
| createdTimestampUsec / userEditedTimestampUsec | created / updated |

## カード DB（`view: cards`、0.5.0）

1 つのメモが 1 つの DB。開くとカードが並びます（☰ で表、▦ でカードに戻る）。本文はふつうの Markdown です：

```markdown
取引先の一覧            ← 最初のカードより前：DB の説明（そのまま残る）

## 山田さん             ← カード 1 枚＝いちばん浅い見出し 1 つ
- 会社: ○○商事          ← 見出しのすぐ下の「- 項目: 値」が項目
- 売上: 1200

よく電話する            ← 残りはカードのメモ（写真 ![[Memo/_files/…]]、深い見出しも可）
```

- 新しいカードは、ほかのカードで使っている項目が最初から入ります。項目は自由に足し引きできます。
- 検索（名前・項目・メモ）、並べ替え（名前・各項目。数字は数として）、写真があればカードの表紙に。
- Obsidian では見出しと箇条書きのまま。Dataview を使う人は `- 会社:: ○○` の形ではない点に注意（Memo は `: ` 1 つ）。

## 表計算（`view: sheet`、0.6.0）

メモの**最初の表**をマス目で編集します。前後の文はそのまま残ります。

- 行・列の追加・挿入・移動・複製・削除、列での並べ替え（数字は数として。`1,200`・`¥300`・`45%` も数）、右寄せ（`---:`）。
- 下の段に列ごとの合計・平均・最大・最小・件数（押すたびに切り替え）。**計算した値は書き込みません**（ふつうの表のまま）。式（`=A1+B2`）は持てません。
- Enter で下へ（最後の行なら行を足す）、Tab で右へ。↶ で元に戻す。
- 🖼 で選んだマスに写真（マスの中身は `![[Memo/_files/…]]`）。写真のマスを押すと 変える・消す。

## スライド（`view: slides`、0.7.0）

本文を `---`（前後に空行）で区切った 1 つずつが 1 枚。Obsidian の Advanced Slides や Marp と同じ書き方です。

```markdown
# 企画の説明             ← 1 枚目（文が無ければ表紙として大きく真ん中に）

---

## 目的                  ← 見出しがスライドのタイトル
- 売上を伸ばす
- 知ってもらう

![[Memo/_files/a.jpg]]   ← 1 行だけの写真：文があれば横に、写真だけなら大きく（4 枚まで並ぶ）
```

- 文のすぐ下の `---` は見出しの記号なので区切りにはなりません（必ず空行をはさむ）。コードの中の `---` も区切りになりません。
- ▶ 再生：画面いっぱい・横向き。右側をタップ（左へスワイプ）で次、左側で前。
