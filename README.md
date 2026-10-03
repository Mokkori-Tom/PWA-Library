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
- `20` — ファイル入力からカメラで撮る
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
- 画像を受け付けるファイル入力 (`<input type="file" accept="image/*">`) から
  カメラで撮る。撮影は端末のカメラアプリに任せるので **`CAMERA` 権限は宣言
  しない**。ミニアプリに渡るのは利用者が撮った 1 枚だけで、ライブ映像
  (`getUserMedia`) は引き続き拒否する
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

## AI にアプリを作らせるときのプロンプト

PWA Library の中は通常のブラウザと条件が違う（通信できない、カメラのライブ映像が
使えない、など）。AI にミニアプリを作らせるときは、下の文面の【作りたいアプリ】を
書き換えて渡すと、そのまま取り込める zip になりやすい。どの項目も、実機で実際に
踏んだ失敗から来ている。

```text
Android の「PWA Library」というアプリで動かす Web アプリを作ってください。
PWA Library は、zip にまとめた Web アプリを取り込み、端末内だけで動かすアプリです。
通常のブラウザとは条件が違うので、下の制約を必ず守ってください。

【作りたいアプリ】
（ここに作りたいものを書く。例：買い物リスト。品目の追加・チェック・削除ができ、
 内容は次に開いたときも残っている。CSV で書き出せる。）

【絶対に守る制約】
1. インターネットに一切つながりません。CDN、Web フォント、外部 API、外部の画像や
   スクリプトは使えません。必要なものはすべて zip の中に入れてください。
   ライブラリを使う場合も、ファイルを同梱するか、使わずに素の HTML / CSS /
   JavaScript で書いてください。
2. zip の直下に index.html を置いてください。
3. ファイルの参照はすべて相対パスにしてください（"./app.js" など）。
   "/app.js" のような先頭スラッシュのパスは使わないでください。
4. ビルド手順が不要な形にしてください。zip を展開したものがそのまま動くこと。
   ファイル数は少ないほどよく、index.html 1 つに CSS と JavaScript を
   まとめても構いません。
5. Service Worker は入れないでください（すべて端末内にあるので不要です）。

【index.html の head に入れるもの】
- 先頭付近に <meta charset="utf-8">
- <meta name="viewport" content="width=device-width, initial-scale=1">
- <title> にアプリ名（"Document" などの既定値のままにしない）
- <link rel="manifest" href="./manifest.json">

【manifest.json】
- name と short_name：アプリ名
- id：このアプリ固有の文字列。バージョン番号や日付を入れず、今後も変えないこと
  （例 "/kaimono-list"）。更新のとき、同じアプリかどうかの判定に使われます。
- start_url："./index.html"、scope："./"、display："standalone"
- icons：192x192 と 512x512 の PNG。PNG ファイルを作れない場合は、
  base64 の PNG を data: URI として icons の src に入れてください。
  SVG だけのアイコンは表示されません。

【使える機能】
- localStorage、IndexedDB（アプリを閉じても、更新しても残ります）
- <input type="file"> によるファイルの読み込み
- 写真の撮影：<input type="file" accept="image/*"> を押すと、利用者は
  「写真を撮る」か「ファイルを選ぶ」を選べます。capture 属性を付けると
  すぐカメラが開きます。
- QR コード・バーコードの読み取り：撮った写真を BarcodeDetector に渡します。
  使う前に 'BarcodeDetector' in window を確かめ、無ければその旨を画面に
  表示してください。
    const bitmap = await createImageBitmap(file);
    const codes = await new BarcodeDetector({ formats: ['qr_code'] }).detect(bitmap);
- ファイルの保存：Blob を URL.createObjectURL で URL にし、<a download="名前">
  をクリックさせる方法。保存先を選ぶ画面が出ます。
- Web Worker、WebAssembly、Canvas、WebGL、フルスクリーン

【使えない機能】
- fetch や XMLHttpRequest による外部への通信
- カメラのライブ映像（getUserMedia）。映像を見ながらの連続読み取りはできません。
  撮影は上の <input type="file"> を使ってください。
- 通知（Notification）、マイク、位置情報
- btoa() に日本語を直接渡すこと（例外になります。TextEncoder を使ってください）

【QR コードの中身を使うとき】
- 読み取った文字列をそのまま URL として開かないでください。
  このアプリ用に決めた形式（例 "form:点検A"）に合うものだけを受け付け、
  それ以外は「このアプリ用の QR コードではありません」と表示してください。

【画面の作り方】
- スマートフォンの縦画面（幅 360px 程度）で使いやすいこと。
- ボタンなどの押す部分は 44px 以上の大きさにしてください。
- 画面の上端約 40px はステータスバーと重なることがあるので、
  そこに文字や操作部品を置かず、余白にしてください。
- 画面のどこかに小さくバージョン（例 "v1"）を表示してください。
  更新が反映されたかを確かめるために使います。
- エラーが起きたら画面に表示してください。window.onerror で捕まえて、
  メッセージを画面の下などに出すこと。

【出力】
- すべてのファイルを省略せずに出力してください。
- zip ファイルを作れる場合は、zip にまとめて渡してください。作れない場合は、
  ファイルごとに名前と中身を示してください。
- 最後に、このアプリが保存するデータと、その保存場所（localStorage のキー名など）
  を一覧にしてください。
```

フォルダへの読み書き（`showDirectoryPicker`）も使えるが、上の文面には入れていない。
フォルダに書き出すアプリを作らせるときは、その旨を【作りたいアプリ】に書き足す。
QR の読み取りは WebView の `BarcodeDetector` に頼っており、確認したのは
Galaxy S24 のみ。

## ライセンス

MIT License。全文は `LICENSE` を参照。
