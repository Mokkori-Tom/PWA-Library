# 取り込みテスト手順

```
python3 samples/build-samples.py
```

`samples/dist/` に全 zip が出る。`samples/hello/` と `samples/diag/` はソース、
`01`〜`18` は生成専用。番号順に取り込む必要はないが、`09` と `10` は
**失敗するのが正解**なので取り違えないこと。

失敗したときに見る場所を右端に書いてある。

---

## 中核

### diag.zip — WebView 診断
自動判定の表と手動確認カード。まずこれを通す。

| 項目 | 期待 | 外れたときに見る場所 |
|---|---|---|
| isSecureContext | OK | `AppAssetRegistry`（https 扱いになっていない） |
| localStorage | 起動のたびに増える | `AppEntity.uuid` / `AppAssetRegistry.domainFor` |
| IndexedDB / Cache Storage | OK | WebView 設定 `domStorageEnabled` |
| SW 登録 | OK | `WebAppActivity.configureServiceWorkers` |
| 自 origin への fetch | OK | `LocalFilePathHandler` |
| **外部への fetch** | **NG（遮断が正常）** | OK なら INTERNET 権限が復活している |
| ES module (.mjs) | OK | `LocalFilePathHandler.MIME_TYPES` |
| Web Worker / WASM / WebGL | OK | — |
| Notification API | なし（正常） | — |
| Geolocation / getUserMedia | 拒否 | 無反応なら `WebChromeClient` |
| 横方向のはみ出し | なし | — |

手動カード:

| 操作 | 期待 | 外れたときに見る場所 |
|---|---|---|
| ファイル選択（単一・複数） | ピッカーが開き名前が出る | `onShowFileChooser` |
| 外部リンク / window.open | **外部ブラウザ**が開く | `shouldOverrideUrlLoading` |
| ダウンロード | 「ダウンロードできません」トースト | `setDownloadListener` |
| 履歴 3 つ→戻る | 3→2→1 と戻ってから閉じる | `OnBackPressedCallback` |
| requestFullscreen | ステータスバーが消える | — |

### エクスポート
どのアプリでもよいので詳細から:

| 操作 | 期待 |
|---|---|
| zip を保存 | 保存先を選ぶ画面が出て、アプリ名の zip が書き出される |
| zip を共有 | 共有シートが出る。ファイル名が `<uuid>.zip` ではなくアプリ名になっている |
| 書き出した zip を取り込み直す | 同じ manifest id なので更新扱いになり、内容が変わらない |

### hello-pwa.zip — origin 永続化と更新
取り込む → 数値を増やす → 再取り込み。**数値が残ったまま**バージョンが上がれば
`ResetPathHandler` が SW キャッシュだけ落とせている。

---

## 構造テスト（インストーラ側）

| zip | 狙い | 期待 | 外れたときに見る場所 |
|---|---|---|---|
| `01-wrapped` | ルート自動検出 | 起動する。`__MACOSX` と `.DS_Store` が展開されない | `ZipInstaller.inspect` / `isNoise` |
| `02-no-manifest` | manifest なしの名前 | 名前が「現場メモ & ログ」、頭文字タイル | `HtmlHead` / `IconStore.letterBitmap` |
| `03-no-viewport` | viewport 注入 | clientWidth < 600px で OK 表示 | `WebAppActivity.onPageFinished` |
| `04-subdir-start` | start_url 尊重 | `pages/start.html` が開く | `AppRepository.resolveStartUrl` |
| `05-fullscreen` | display / theme_color | システムバーが消える | `WebAppActivity.applyChrome` |
| `06-svg-icon` | SVG アイコン | 落ちずに頭文字タイル | `IconStore.candidatePaths` |
| `07-assets` | MIME 判定 | 全行 OK | `LocalFilePathHandler.MIME_TYPES` |
| `08a` / `08b` | app shell 型 SW の更新 | 下記参照 |
| `11a` / `11b` / `11c` | 生成された manifest の相対 id | 下記参照 |
| `12-folder-write` | フォルダへの継続書き込み | 下記参照 | `WebAppActivity.RESET_SCRIPT` |
| `13-picker-modes` | ピッカーの 2 つの意味 | 下記参照 | `FileSystemShim.hasActivation` |
| `14-download` | 書き出したファイルの保存 | 下記参照 | `DownloadShim` / `DownloadBridge` |
| `15-link-icon` | `<link rel="icon">` を辿る | アイコンが青緑の四角 | `HtmlHead` / `IconStore.candidatePaths` |
| `16-title-generic` | 雛形の既定 title を捨てる | 名前が `16-title-generic` | `HtmlHead` の `GENERIC_TITLES` |
| `17-data-icon` | manifest 内の `data:` URI アイコン | アイコンがオレンジの四角 | `IconStore.decodeDataUri` |
| `18a` / `18b` | 同名で他に手がかりのない zip | 下記参照 | `AppRepository.complete` の `findByAnyName` |
| `19a` / `19b` | `<link rel="manifest">` からしか辿れない manifest | 下記参照 | `ZipInstaller.readManifest` |

### 08 の手順（app shell 型 SW での更新）
編集は不要。2 つの zip を順に取り込むだけ。

1. `08a-appshell-BUILD1.zip` を取り込んで起動
2. 「登録済み・制御中」になるまで待つ（未制御なら一度閉じて開き直す）
3. `08b-appshell-BUILD2.zip` を取り込む
4. 起動する

期待: 「更新を適用しています…」のトーストが出て、自動でリロードされ `BUILD-2` になる。

2 つは同じ manifest id なので、どちら向きにも往復して試せる。

| 一覧の個数 | トースト | 判定 |
|---|---|---|
| 2 個並ぶ | — | 更新として認識されていない。`AppRepository` の manifest_id 照合 |
| 1 個 | 出ない | 更新フラグが立っていない。`needs_storage_reset` の DB 経路 |
| 1 個 | 出る → ビルド番号が変わる | 成功 |
| 1 個 | 出る → 変わらない | `RESET_SCRIPT` が SW を破れていない |

### 11 の手順（生成された manifest の id）
実際の LLM 出力が `"id": "./index.html?v=5"` を使っていたことへの退行テスト。
順に取り込む。

| 取り込む | 期待 | 外れたら |
|---|---|---|
| `11a-relid-A-v1` | 一覧に「11 相対 id A」が 1 個 | — |
| `11b-relid-B-v1` | 「11 相対 id B」が増えて **2 個**。A は無傷 | **重大**。同じ id の別アプリが統合されている |
| `11c-relid-A-v2` | A の**更新**。個数は 2 個のまま | id のバージョン差で別アプリ扱いになっている |

`AppRepository.matchByManifest` / `normalizeManifestId` を見る。

### 12 の手順（フォルダ書き込み）
標準の File System Access API だけを使ったページ。生成された PWA がそのまま
動くかの確認も兼ねる。

1. 取り込んで起動 → 「フォルダを選ぶ」
2. SAF で **Documents** など、または任意のサブフォルダを選ぶ
   （**Download は Android 側が拒否する**ので別のフォルダで）
3. 「1 行追記」を数回。行が増えていく
4. 「中身を一覧」でファイルが見える
5. **アプリを閉じて開き直す** → 「前回のフォルダに再接続しました」と出る
6. さらに追記 → 前の行が残ったまま増える
7. ファイルマネージャで `pwalib-log.txt` を直接開いて中身を確認

| 外れたら | 見る場所 |
|---|---|
| フォルダ選択が開かない | 診断行を見る。`bridge=なし` なら `addJavascriptInterface`、`shim=なし` なら `ShimPathHandler` |
| 選んでもエラー | `onFolderPicked` の `takePersistableUriPermission` |
| 再起動で再接続しない | 権限が永続化できていない。同上 |
| 追記のたびに上書きされる | `FileSystemShim` の `seek` / `keepExistingData` |
| 一覧が空 | `FolderAccess.list` |

8. もう一度「フォルダを選ぶ」→ **選択画面が出る**（別のフォルダに変えられる）。
   選び直したフォルダに切り替わり、追記が 1 行だけの新しいファイルになる
9. **選択画面表示中の Activity 回収**。開発者向けオプションの「アプリ」セクションで
   「アクティビティを保持しない」を ON（「ウィンドウ管理」ではない。adb なら
   `adb shell settings put global always_finish_activities 1`）。SAF が前面に出た
   時点でこの Activity は破棄される

   | 見る場所 | 期待 |
   |---|---|
   | 戻ったとき | クラッシュしない。ページは作り直されている（`log` が空） |
   | 「フォルダ:」欄 | **今選んだフォルダ**。前のフォルダ名や「未選択」なら競争に負けている（HANDOVER「4.」参照）|
   | 追記 | 今選んだフォルダに 1 行だけ書かれる |
   | もう一度「フォルダを選ぶ」 | 選択画面が出る。「処理中です」なら `pendingDirectoryRequest` が復帰後に残っている |

   確認したら開発者向けオプションを OFF に戻す

選択画面での戻るキーは、階層を一つ上に遡る。これは Android のファイル選択画面
(DocumentsUI) 自身の動作で、こちらからは制御できない。上まで遡るとアプリに戻り、
「選択を取り消しました」と出る。

許可の取り消しは詳細画面から。複数のフォルダを許可した場合、再接続では
**最後に選んだフォルダ**が使われる。

### 02 / 15 / 16 の手順（manifest なしの zip）
3 本とも manifest.json を持たないので、名前もアイコンも index.html からしか
取れない。**続けて取り込んで、一覧のスクリーンショット 1 枚**で 3 つとも判定できる。

**作り直した zip を試す前に、前のものを一覧から削除すること。** manifest の無い
zip は同一バイトのときだけ更新扱いになる（`AppRepository` の `findByZipHash`）。
中身を変えて作り直すと別アプリとして増えるので、同じ名前のカードが 2 枚並び、
どちらを見ているのか分からなくなる。

| 取り込む | 一覧での名前 | 一覧でのアイコン |
|---|---|---|
| `02-no-manifest` | **現場メモ & ログ**（`<title>`）| 頭文字タイル |
| `15-link-icon`（rev 2）| リンクされたアイコン | **青緑（teal）の四角** |
| `16-title-generic` | **16-title-generic**（ファイル名）| 頭文字タイル |

| 外れたら | 意味 |
|---|---|
| 02 が `02-no-manifest` | `<title>` を読めていない。`ZipInstaller.readHtmlHead` |
| 02 が `現場メモ &amp; ログ` | 実体参照を復号していない。`HtmlHead` の `unescape` |
| 02 の名前が化けている | 文字コードの判定。`HtmlHead` の `decode` |
| 15 が頭文字タイル | link を辿れていないか、512 の候補で諦めている。`IconStore.extract` |
| 15 が黒や赤の四角 | `15` の **rev 1** を取り込んでいる。作り直すこと |
| 16 が `Document` | 雛形の既定値をそのまま採用している。`GENERIC_TITLES` |

`15`（rev 2）は候補を 3 つ並べてある。document 順では `favicon.ico` が先頭、
宣言サイズでは `assets/not-an-image.png` が最大で、**正解は 3 番目の 192**。
つまり「順に試して、デコードできたものを採る」が効いていないと通らない。

飛ばされる側を**画像でないファイル**にしてあるのには理由がある。rev 1 では
途中で切れた PNG を使っていたが、`BitmapFactory` はそれを撥ねずに**読めた行まで
描いた bitmap**を返す。壊れたアイコンは弾かれずに壊れたまま採用される
（HANDOVER「4.」）。**Mac 上の `ImageIO` は同じファイルを例外で撥ねるので、
デコードの可否をあちらで代用してはいけない。**

`HtmlHead` は `android.*` に依存していないので、正規表現と文字コードの判定は
Mac 上で単体実行して確かめられる。手順は HANDOVER の「5. 作業時の注意」。
確かめられるのはそこまでで、**`BitmapFactory` が何を読めるかは端末でしか
分からない**。

### 17 の手順（manifest に埋め込まれたアイコン）
アイコンのファイルは 1 つも入っていない。manifest の `icons` に `data:` URI が
2 つ書いてあるだけで、**先に並ぶ 512 は SVG**、オレンジの PNG は 192 で 2 番目。

| 一覧のアイコン | 意味 |
|---|---|
| **オレンジの四角** | OK |
| 頭文字タイル | data URI を復号していない。`IconStore.decodeDataUri` |
| 赤い四角 | SVG の data URI をラスタライズできている（残作業 4 が済んでいれば正しい）|

`<link rel="icon" href="data:...">` 側は別に用意していない。`HtmlHead` が
data URI をそのまま返すことは Mac 上で確認済みで、その先は manifest 由来の候補と
同じ経路（`isRasterisable` → `decodeCandidate`）を通るため。

### 18 の手順（同じ名前の zip が来たとき）
2 本とも manifest を持たず、`<title>` だけが「棚卸しメモ」と名乗っている。
バイト列は違うのでハッシュでも一致しない。名前しか手がかりが無い状態で、
**同じアプリの新しい版なのか、たまたま名前が同じ別物なのかは zip から分からない**。

起動回数のカウンタが答えを見えるようにしている。更新は uuid（= origin）を
使い回すので localStorage が残り、別アプリとして追加すると 1 から始まる。

**v1 / v2 の色はページの中身に出る大きな文字で、アイコンではない。** どちらの zip も
アイコンを持たないので頭文字タイルになり、タイルの色は名前から決まる
(`IconStore.colorFor`)。**名前が同じ 2 枚は必ず同じ青い「棚」になる**ので、
一覧では区別できない。詳細ダイアログの**サイズ**で見分ける
（v1 が 1,379 B、v2 が 1,497 B）。追加日の時刻でもよい。

1. `18a-samename-v1` を取り込む → **何も尋ねられずに追加**。開いて閉じてを繰り返し、
   起動回数を 2 か 3 まで進める（ページに青い v1 が出る）
2. `18b-samename-v2` を取り込む → **「同じ名前のアプリがあります」** が出る
3. **「別のアプリとして追加」** を選ぶ → カードが **2 枚**。**両方開く**。
   片方は青い v1 で起動回数が続き、もう片方は**赤い v2 で起動回数が 1**
   （別 origin なので当然）
4. v2 の方（サイズ 1,497 B）のカードを削除する
5. もう一度 `18b-samename-v2` を取り込む → またダイアログ →
   **「棚卸しメモ」を更新** を選ぶ → カードは **1 枚のまま**、開くと赤い v2 で
   **起動回数が 1 の続き**（3 なら 4）。データが残ったまま中身が入れ替わっている

| 外れたら | 意味 |
|---|---|
| 2 でダイアログが出ない | `findByAnyName` に届いていない。`mayAsk` が落ちている |
| 更新を選んだのに 2 枚になる | `resolveChoice` に uuid が渡っていない |
| 更新後に起動回数が 1 に戻る | uuid を使い回せていない。origin が変わっている |
| ダイアログを閉じたあと | 取り込みは取り消される。キャッシュに zip が残らないこと |
| 追加した 2 枚がどちらも v1 | **重大**。預けた zip ではなく前のものを展開している。`AppRepository.complete` の `awaitingChoice` |

**尋ねすぎないことも確認する。** `08a` → `08b` は manifest id が一致するので
**ダイアログが出てはいけない**。出るなら確信のある更新まで質問に落ちている。

### 19 の手順（`<link rel="manifest">` からしか辿れない manifest）
manifest は入っているが、名前が `site.webmanifest` で、場所を教えているのは
`<link rel="manifest">` だけ。favicon ジェネレータが吐くのがこの名前なので、
手書きの PWA では珍しくない形。

rev 1 では **この manifest は無かったことになっていた**（実機で確認済み。4 つの
観測が全部そちらを指した）。`HtmlHead.manifestHref` と
`InstallPaths.entryForHref` を入れて**辿るようにしたので、いまは退行テスト**。
manifest 側が正解になる。

manifest とページの `<head>` は**わざと食い違わせて**ある。どちらを読んだかが
一覧の名前とアイコンに出る。

| 見えたもの | 意味 |
|---|---|
| 名前 **19 リンクされた manifest** / **紫**のアイコン | **OK**。`<link rel="manifest">` を辿れている |
| 名前 **手書きの棚卸しツール** / **緑**のアイコン | **退行**。rev 1 のときの挙動に戻っている |

**rev 1 の 19 が一覧に残っていたら先に消すこと。** rev 1 は manifest を読めて
いなかったので `manifest_id` が空で、名前も「手書きの棚卸しツール」で入っている。
rev 2 とはどの手がかりでも一致しないため、消さずに取り込むとカードが増える。

1. `19a-linked-manifest` を取り込む → 名前が **19 リンクされた manifest**、
   アイコンが **紫**
2. 起動する → `display: fullscreen` が効いて **ステータスバーが消える**。起動回数が 1
3. 閉じて開き直し、起動回数を 3 まで進める
4. `19b-linked-manifest-v2` を取り込む

**4 が本題。** 2 本は同じ `id`（`test.linkedmanifest`）を名乗っている。

| 4 で起きたこと | 意味 |
|---|---|
| **何も尋ねられずに更新**され、赤い v2 / 起動回数 4 | **OK**。id が読めている |
| 「同じ名前のアプリがあります」が出る | **退行**。id が読めておらず、同一性がハッシュに落ちている |
| カードが 2 枚に増える | 名前も一致していない。`HtmlHead` の `<title>` を疑う |

2 枚並んだときは詳細ダイアログのサイズで見分ける（v1 が 3,338 B、v2 が 3,520 B）。

### 名前とアイコンの編集
zip は要らない。詳細ダイアログの上段に並ぶ。

| 操作 | 期待 |
|---|---|
| 名前を変更 | 一覧の名前が変わる。ピン留め済みならホーム画面のラベルも |
| 名前を元に戻す | zip が名乗る名前に戻り、この行自体が消える |
| アイコンを変更 | 画像を選ぶと一覧に反映。ピン留め済みならホーム画面にも |
| アイコンを元に戻す | zip 由来のアイコンに戻り、この行が消える |

**編集が更新で消えないことが本題。** ここが崩れると、直した名前が取り込みのたびに
戻る。

1. `11a-relid-A-v1` を取り込む → 「11 相対 id A」
2. **改名する**（「棚卸し 2026」など）
3. `11c-relid-A-v2` を取り込む

| 一覧 | 判定 |
|---|---|
| 1 個のまま、名前は「棚卸し 2026」 | 成功。`import_name` で照合し、改名を守れている |
| 2 個に増えた | **重大**。`import_name` での照合が効いていない（`AppDao.findByAnyName` ではなく `findByManifestIdAndImportName` を見る）|
| 1 個だが名前が「11 相対 id A」に戻った | `nameIsCustom` を見ていない |

アイコンも同じ形で確かめる。`02-no-manifest` のアイコンを変えてから `02` を
取り込み直し、選んだ画像のままなら `iconIsCustom` が効いている。

### 13 の手順（操作の有無で変わる showDirectoryPicker）
`12` は再接続に `pwaLibrary.files.folders()` を使うので、`showDirectoryPicker()`
自身の「操作なし」分岐を通らない。こちらは同じ関数を両方の状態で呼ぶ。
transient activation は数秒で切れるので、遅らせて呼べば操作なしの呼び出しになる。

1. 取り込んで起動。許可がまだ無ければ「読み込み時の呼び出しは省略しました」と出る
2. **「1. ユーザー操作で呼ぶ」** → **選択画面が出る**。フォルダを選ぶ
3. **「2. 8 秒後に呼ぶ」** → カウントダウン中は**画面に触らない**
   → **選択画面が出ず**、フォルダ名が返れば OK
4. **アプリを閉じて開き直す** → 読み込み時の呼び出しが走り、
   「選択画面なしで <名前> に再接続」と出れば OK

`isActive` は呼び出しのたびに下のログに出る。

| 外れたら | 意味 |
|---|---|
| 2 や 4 で選択画面が出る | 操作なしと判定できていない。`userActivation=なし` なら WebView が非対応で、常に出す側に倒れている（仕様どおり） |
| 2 が「触ってしまったので判定できません」 | カウントダウン中に画面に触れた。やり直す |
| 4 で再接続できない | `FileBridge.listGrants` |
| 1 で選択画面が出ない | 許可済みフォルダを黙って返している。`FileSystemShim.pick` の `force` 分岐 |

### 14 の手順（ダウンロード）
fieldform と同じ形にしてある。blob URL を `<a download>` に渡し、**1.5 秒後に
revoke** し、ページの CSP は `connect-src 'none'`。

1. 診断行に **「blob URL への fetch: 遮断」** と出る。これが出るのが正常で、
   blob を `fetch` で読み直す実装が使えない理由そのもの
2. **「CSV を書き出す」** → 保存先を選ぶ画面 → 選ぶ → 「sample.csv を保存しました」。
   ファイルマネージャで中身が 3 行あること
3. **「2 MB を書き出す」** → 保存したファイルが **2,097,152 バイト**。分割して
   渡しているので、途中で切れていればサイズでわかる
4. **「data URL で書き出す」**（base64）と **「data URL（base64 以外）」**
   （パーセントエンコード）→ `from-data-url.txt` と `percent-encoded.txt`。
   どちらもシムがクリックを捕まえて Blob に変換している
5. **「アプリ内のファイルを保存」** → `notes.txt` が保存される
6. 保存先を選ぶ画面で**戻る**→「保存を取り消しました」。ファイルは残らない
7. 「アクティビティを保持しない」を ON にして 2 をやり直す →
   回収されても保存できる（staging はキャッシュに置いてあるので生き残る）

| 外れたら | 見る場所 |
|---|---|
| 「データが見つかりません」 | revoke が先に効いている。`DownloadShim` の保持期間 |
| ファイル名が `downloadfile.bin` | `<a download>` の値を拾えていない。`DownloadShim` の click ハンドラ |
| 2 MB が途中で切れる | `DownloadBridge.chunk` |
| 何も起きない | まずページ側の例外を疑う（`window.onerror` が拾って画面に出す）。それが無ければ `setDownloadListener` に届いていない |

---

## 拒否されるべき zip

| zip | 期待 | 通ってしまったら |
|---|---|---|
| `09-traversal-MUST-FAIL` | 「zip に不正なパスが含まれています」で拒否 | **重大**。`ZipInstaller` のガードが機能していない |
| `10-no-index-MUST-FAIL` | 「index.html が見つかりません」で拒否 | `ZipInstaller.inspect` |

09 は `../../databases/pwa_library.db` と
`../../../../data/data/io.github.mokkori_tom.pwalibrary/files/pwned.txt` を含む。
**取り込みが成功したらそこで中断すること。** アプリ内部を上書きされた状態になる。

---

## 実際の PWA
合成テストでは出ない壊れ方があるので、最後に実ビルドを入れる。ビルド済みの SPA
（Vite / Next の静的出力）は `<script type="module">`・絶対パス参照・
history フォールバックを一度に踏むので効率が良い。

`samples/real-vite/` がその 1 本（Vite + React Router + Workbox）。生成方法は
そこの README を見る。**npm が要る**ので `build-samples.py` には入れていない。

1. `real-vite-BUILD1.zip` を取り込む → 名前が「実 PWA テスト (Vite SPA)」、
   アイコンが画像（頭文字タイルなら `manifest.webmanifest` を拾えていない）
2. 起動 → **現在のパスが `/`**（`/index.html` だと SPA は 404 を出す）
3. 「遅延チャンク」→ 実行時のチャンク取得
4. 診断行が「登録済み・**制御中**」になるまで（未制御なら開き直す）
5. 「About」→「このパスで再読み込み」→ **About が復帰する**。SW が未制御の状態でも
   復帰すれば `WebAppActivity` の history フォールバックが効いている
6. `real-vite-BUILD2.zip` を取り込む → 一覧は 1 個のまま `BUILD-2` に変わり、
   **カウントは残る**

| 外れたら | 見る場所 |
|---|---|
| 起動直後に 404 | `AppAssetRegistry.urlFor` |
| 再読み込みで白画面 | `WebAppActivity.shouldInterceptRequest` の 404 フォールバック |
| 画面が真っ白（初回から） | `LocalFilePathHandler.MIME_TYPES`、module script の配信 |

### Next の静的出力
`samples/real-next/`。Vite の SPA と違い**ルートごとに HTML が出る**（`/about` は
`out/about.html`）。Service Worker も無いので、パスの解決は完全にこちら任せになる。

1. `real-next-BUILD1.zip` を取り込む → 名前が「実 PWA テスト (Next 静的出力)」
2. 起動 → 現在のパスが `/`、ホームが描画される
3. 「About」「遅延チャンク」→ クライアント遷移で表示される（`.txt` の RSC
   ペイロードを取りに行くので、MIME が合っていないとここで止まる）
4. **`/about` で「このパスで再読み込み」** → ここが本番。実ファイルは
   `about.html` なので、`/about` を要求しても当たらない
5. カウントを増やして `real-next-BUILD2.zip` を取り込む → `BUILD-2` に変わり、
   カウントは残る

| 外れたら | 見る場所 |
|---|---|
| 4 でホームが出る（URL は `/about` のまま） | `WebAppActivity.resolveNavigation` が `about.html` を見つけられていない |
| 3 で遷移しない | `LocalFilePathHandler.MIME_TYPES` の `txt` |
| アイコンが頭文字タイル | `manifest.webmanifest` を拾えていない |

### base をサブパスにしたビルド
`samples/real-vite/` を `APP_BASE=/app/` でビルドしたもの。ソースは同じで、
出力だけが `/app/` 配下前提になる。生成方法はそこの README。

rev 1 では**白画面だった**（実機で確認済み）。`/app/assets/…` の絶対パスが 404 に
なり、サブリソースの 404 は肩代わりの対象外（`isForMainFrame` のみ）だったため。
それでいて `manifest.webmanifest` はルート直下なので**見つかり**、icons が相対の
まま出るので、**一覧では名前もアイコンも正常に見えていた**。取り込みが成功した
ようにしか見えないのが厄介なところだった。

いまは `InstallPaths.installSubpath` が manifest の `scope` / `start_url` を読み、
**その接頭辞が zip の中に無いときだけ `appDir/app/` に展開する**。配信も
`urlFor` も SW スコープも既存のまま辻褄が合う。

1. `real-vite-base-BUILD1.zip` を取り込む → 名前「実 PWA テスト (base=/app/)」、
   アイコンは青い四角
2. 起動する → **ホームが描画される**。「現在のパス」が **`/`**
   （`basename` が `/app/` なので、ルーターから見た `/app/` は `/`）
3. 「遅延チャンク」→ チャンクが取れる
4. 診断行が「登録済み・**制御中**」になるまで（未制御なら開き直す）
5. 「About」→「このパスで再読み込み」→ **About が復帰する**
6. `real-vite-base-wrapped.zip` を取り込む

**6 が二つ目の狙い。** 中身は 1 と同じ出力を `app/` フォルダごと包んだもの。
`ZipInstaller.inspect` が `app/` を剥がしたあと、同じ判定に掛かって同じ場所に
戻される。**包み方が違っても同じ結果になる**のが正しい。

| 外れたら | 意味 |
|---|---|
| **白画面** | 接頭辞が効いていない。`InstallPaths.installSubpath`（`scope` を読めているか）|
| 起動はするがアイコンが頭文字タイル | `IconStore.extract` に `contentDir` ではなく `appDir` を渡している |
| 5 で白 | `resolveNavigation` の最後の候補にアプリの根が入っていない（`appRootPath`）|
| 6 でカードが **2 枚**に増えた | manifest id (`real.vite.spa.base`) での照合が効いていない |

**`real-vite-BUILD1/2`（既定 base）と `real-next` も一度通すこと。** 接頭辞の判定は
全アプリの展開経路に入ったので、`scope: "/"` が今までどおり「接頭辞なし」に落ちる
ことを確かめる意味がある。

#### SW なしの入れ子（`real-vite-base-nosw.zip`）

**5 で誰が答えたかは、この 1 本でしか分からない。** SW が制御していると Workbox の
`navigateFallback` が答えてしまうので、`resolveNavigation` にアプリの根を足した
分が効いているかは区別できない。この zip は manifest と `base` はそのままに
**Service Worker を持たない**（`basePath` を付けた Next の静的出力と同じ形）。

1. 取り込む → 名前「実 PWA テスト (base, SW なし)」で、**別のカード**として増える
   （id が違うので更新にはならない）
2. 起動 → ホームが描画され、診断行の Service Worker が **「未登録」**
   （「登録済み」と出たら zip の作り方が違う。この確認は成立しない）
3. 「About」→ **「このパスで再読み込み」**

| 3 で起きたこと | 意味 |
|---|---|
| **About が復帰する** | `WebAppActivity.appRootPath` が効いている。SW がいないので、答えられるのは native の 404 フォールバックだけ |
| **白画面** | 入れ子のアプリ根が候補に入っていない。`resolveNavigation` |

4. 「遅延チャンク」でも同じことをする（`/heavy` で再読み込み）

### fieldform（他人が書いた実アプリ）
ビルド不要の素の静的サイト。オフライン前提のフォーム作成・データ収集ツールで、
**CSP で `connect-src 'none'`**、Service Worker なし、`start_url` と `scope` が
`"."`、`manifest.webmanifest`、アイコンは `purpose: "any maskable"`。
こちらが書いていないコードなので、思い込みの入っていない検体になる。

```
git clone --depth 1 https://github.com/Sreenivas-Sadhu-Prabhakara/fieldform /tmp/fieldform
(cd /tmp/fieldform && zip -qr ~/Downloads/fieldform.zip . -x '.git/*')
```

MIT。リポジトリには取り込まず、必要なときに上で作る。

| 操作 | 期待 |
|---|---|
| 取り込み・起動 | 名前が「fieldform」、アイコンが画像。`start_url: "."` でも起動する |
| フォームを作る（フィールド追加・並べ替え・必須） | 動く |
| Collect で保存 → 件数が増える | 動く。閉じて開き直しても残る（localStorage） |
| **CSV / JSON エクスポート** | **保存できる**（確認済み）。保存先を選ぶ画面が出て、ファイル名は `<a download>` のもの（`site-survey-20260830-1023.csv`）。`connect-src 'none'` の下でも読めているのは `DownloadShim` が Blob を保持して FileReader で読むため |
| Export form → Import form | 往復できる |
| **保存を断ったとき** | ファイルは残らないが、**fieldform は「Exported 2 rows to CSV」と成功表示を出したまま**になる。成否をページに返す方法はないので、これは直せない（HANDOVER「4.」）|

エクスポートは fieldform の主機能で、ダウンロード対応（SAF で保存する）を
入れたときの最初の検証対象がここだった。
