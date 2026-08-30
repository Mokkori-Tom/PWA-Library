# 取り込みテスト手順

```
python3 samples/build-samples.py
```

`samples/dist/` に全 zip が出る。`samples/hello/` と `samples/diag/` はソース、
`01`〜`10` は生成専用。番号順に取り込む必要はないが、`09` と `10` は
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
| `02-no-manifest` | manifest なしの退避 | 名前が `02-no-manifest`、頭文字タイル | `ZipInstaller.stage` / `IconStore.letterBitmap` |
| `03-no-viewport` | viewport 注入 | clientWidth < 600px で OK 表示 | `WebAppActivity.onPageFinished` |
| `04-subdir-start` | start_url 尊重 | `pages/start.html` が開く | `AppRepository.resolveStartUrl` |
| `05-fullscreen` | display / theme_color | システムバーが消える | `WebAppActivity.applyChrome` |
| `06-svg-icon` | SVG アイコン | 落ちずに頭文字タイル | `IconStore.candidatePaths` |
| `07-assets` | MIME 判定 | 全行 OK | `LocalFilePathHandler.MIME_TYPES` |
| `08a` / `08b` | app shell 型 SW の更新 | 下記参照 |
| `11a` / `11b` / `11c` | 生成された manifest の相対 id | 下記参照 |
| `12-folder-write` | フォルダへの継続書き込み | 下記参照 | `WebAppActivity.RESET_SCRIPT` |

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

---

## 拒否されるべき zip

| zip | 期待 | 通ってしまったら |
|---|---|---|
| `09-traversal-MUST-FAIL` | 「zip に不正なパスが含まれています」で拒否 | **重大**。`ZipInstaller` のガードが機能していない |
| `10-no-index-MUST-FAIL` | 「index.html が見つかりません」で拒否 | `ZipInstaller.inspect` |

09 は `../../databases/pwa_library.db` と
`../../../../data/data/com.example.pwalibrary/files/pwned.txt` を含む。
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
| **CSV / JSON エクスポート** | **現状は「ダウンロードできません」で失敗するはず**。`<a download>` + blob URL を `setDownloadListener` が断っている |
| Export form → Import form | 出力ができないので、往復は現状確認できない |

エクスポートは fieldform の主機能なので、これが実アプリでのダウンロード対応
（SAF で保存する）の優先度を決める材料になる。
