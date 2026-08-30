# PWA Library

zip でもらった Web アプリを取り込んで、ホーム画面から普通のアプリのように起動できる Android アプリ。

## 設計上の柱

**ローカル HTTP サーバーは使わない。** `WebViewAssetLoader` で各アプリを
`https://app-<uuid>.androidplatform.net/` にマップする。結果として:

- https 扱い = secure context なので Service Worker が正式に動く
- アプリごとに origin が分かれるので localStorage / IndexedDB がブラウザの
  origin 規則だけで自動的に隔離される
- ポート衝突なし、`INTERNET` パーミッション不要

**パーミッションはゼロ。** マニフェストに一つも宣言していない。素性の分からない
zip を実行する以上、外部通信できないこと自体が containment になっている。
`INTERNET` を戻すと、この保証も、CDN を使うアプリだけが動くという中途半端な
状態も、まとめて抱えることになる。

**フォルダ書き込みは SAF 経由。** ミニアプリは標準の File System Access API
(`showDirectoryPicker` など) を呼べる。裏側は `ACTION_OPEN_DOCUMENT_TREE` と
`takePersistableUriPermission()` で、**これもパーミッション宣言を必要としない**。
ユーザーがその場で選んだフォルダだけ、再起動後も含めて読み書きできる。
許可はアプリ単位で `FileBridge` が uuid で絞り、詳細画面から取り消せる。
ネットワークには出られないままローカルのファイルは扱える、という組み合わせ。

`showDirectoryPicker()` はユーザー操作から呼ばれたときは必ず選択画面を出し、
ページ読み込み時など操作を伴わない呼び出しでは許可済みのフォルダを黙って返す
(`navigator.userActivation` で判別)。前者はフォルダを選び直したいという意思表示、
後者は前回の続きへの再接続なので、同じ関数でも意味が違う。

許可の確認は SAF 自身の「フォルダへのアクセスを許可しますか?」に任せている。
アプリ側でも確認していた時期があるが、確認が 3 回続くと読まずに通す方向に
働くのでやめた。ただしシステム側の文言はコンテナアプリ名しか出さないため、
どのミニアプリが要求したかは詳細画面の一覧で確認する。

**origin は uuid から作る。** DB の連番 ID ではない。更新しても uuid を使い回す
ので、アプリが保存したデータが更新で消えない。

## ビルド

JDK 17 と Android SDK が要る。Android Studio (Ladybug 以降) で開くのが早い。

```
open -a "Android Studio" ~/AndroidStudioProjects/pwa-library
```

Gradle wrapper の jar はリポジトリに入れていないので、Android Studio に生成させるか、
Gradle を入れて `gradle wrapper` を一度走らせる。

## 動作確認

```
tools/typecheck.sh --test
python3 samples/build-samples.py
```

`tools/typecheck.sh` は Compose 以外を Mac 上で型チェックし、`--test` で
`HtmlHead` のテストも走らせる。Android Studio 同梱の kotlinc と Gradle の
キャッシュを使うので、テスト用の依存は増やしていない。

`samples/dist/` にテスト用 zip が出る。手順と期待値は **[samples/TESTING.md](samples/TESTING.md)**。

- `diag.zip` — WebView で何が動いて何が動かないかを一覧表示する診断アプリ
- `snake.zip` — 遊べるゲーム。最高スコアが localStorage に残る
- `hello-pwa.zip` — origin 永続化と更新の確認用
- `01`〜`08` — ルート検出 / start_url / MIME / viewport 注入などの構造テスト
- `02`, `15`, `16` — manifest なしの zip の名前とアイコン
- `17` — manifest に埋め込まれた `data:` URI アイコン
- `18a`, `18b` — 同名で他に手がかりのない zip（更新か新規かを尋ねる）
- `19a`, `19b` — `<link rel="manifest">` からしか辿れない manifest
- `09`, `10` — **拒否されるのが正解**（Zip Slip、index.html なし）

`samples/hello/` と `samples/diag/` がソース。編集して再生成すると
`manifest.json` の `id` が同じなので「更新」として扱われ、localStorage は保持される。

## 実装済み (Phase 1 + ホームアイコン + manifest 解析)

- SAF / 共有シート / ファイルマネージャからの zip 取り込み
- 展開 (Zip Slip・zip bomb 対策、ルートフォルダ自動検出、`__MACOSX` 除去)
- `manifest.json` 解析 → name / short_name / description / start_url / display /
  theme_color / icons
- manifest のない zip では index.html の `<head>` から拾う。`<title>` を名前に、
  `<link rel="icon">` をアイコンの候補に。**拒否はしない** — manifest は動作に
  必要でなく（start_url は index.html、display は standalone に落ちる）、
  エラーを見るのは zip を受け取った側で、manifest を足せる立場にないため

  manifest はルート直下の `manifest.json` / `manifest.webmanifest` を先に探し、
  無ければ **`<link rel="manifest">` を辿る**。`site.webmanifest` を使う手書きの
  PWA でも、名前・アイコン・display・start_url・**id** が読める
- Room への保存、2列グリッドの一覧
- WebView 実行 (Service Worker 対応、フルスクリーン、Back 制御、
  レンダラークラッシュ復帰、ファイル選択、requestFullscreen / 動画フルスクリーン)
- ホーム画面へのピン留め (アダプティブアイコン、アプリごとの recents エントリ)
- 削除、同一 manifest id での更新 (更新時に古い Service Worker キャッシュを破棄)
- 取り込み後の名前・アイコンの編集。編集した内容は以降の更新で上書きされない
- サブパス配下前提のビルド (`base: "/app/"`) の展開先の補正
- 名前しか一致しない zip が来たときの「更新か、別のアプリとして追加か」の確認
- zip の保存 (SAF) と共有 (FileProvider)。取り込んだ zip をそのまま渡すので、
  受け取った相手のファイルと 1 バイトも変わらない
- ダウンロードの保存 (blob / data URL / アプリ内ファイル)。SAF で保存先を選ぶ
- File System Access API の模倣 (`showDirectoryPicker` / `getFileHandle` /
  `createWritable` / ディレクトリ列挙)。SAF が裏打ち

## 未実装

- カテゴリ / タグ / 検索 (カラムだけ用意してある)
- SVG アイコンのラスタライズ。Android に SVG デコーダが無く、第三者ライブラリか
  オフスクリーンの WebView が要る。実機で通した実物はすべて PNG を同梱していた
  ので見送った。SVG しか無い zip でも、利用者が自分で画像を選べる

## サブパス配下前提のビルド

`base` を `/app/` に設定してビルドした出力は、`/app/assets/…` という絶対パスを
書く。zip はビルド出力の中身から作られるので、そのままではどこにも当たらず
**アプリが白く開く** —— それでいて `manifest.webmanifest` はルート直下にあるため、
一覧では名前もアイコンも正常に見えてしまう。取り込みが成功したようにしか
見えないのが、この壊れ方の厄介なところだった。

対処は配信側ではなく展開側に置いた。manifest の `scope` / `start_url` が宣言する
接頭辞が **zip の中に無いときだけ**、`appDir/` ではなく `appDir/app/` に展開する。
ビルドが信じている場所にファイルを置くだけなので、配信も起動 URL も
Service Worker のスコープも既存のまま辻褄が合い、他のアプリには何の影響もない。
zip が本当にその名前のフォルダを含んでいる場合は、その言い分を採って何もしない。

## 既知の Android 側の制約

- ピン留め済みショートカットはプログラムから削除できない。アプリ削除時は
  `disableShortcuts()` で無効化するだけで、ホーム画面からはユーザーが消す
- JS から挿入した viewport メタタグはレイアウト幅に反映されない。そのため
  `LocalFilePathHandler` が配信時に HTML へ書き込んでいる
- 特定 origin の Service Worker / Cache Storage を消す API がない。更新後の
  初回ロードで `evaluateJavascript` からページ内で消してリロードしている
- WebView は Notifications API に対応していない
- `ACTION_OPEN_DOCUMENT_TREE` では内部ストレージのルート、`Android/data`、
  `Android/obb` に加えて **Download も選べない**（「プライバシーを保護するため、
  別のフォルダを選択してください」と出る）。Documents や DCIM、任意の
  サブフォルダは選べる
- ダウンロードは `ACTION_CREATE_DOCUMENT` で保存先を選ばせる。blob はページの
  CSP が `fetch` を禁じていても読めるよう、`URL.createObjectURL` を包んで Blob を
  保持し FileReader で読む。ただし成否をページに返す方法はない
