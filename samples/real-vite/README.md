# 実 PWA テスト (Vite SPA)

**合成テストでは出ない壊れ方**を拾うためのサンプル。中身は普通の Vite アプリで、
価値があるのは書いたコードではなく**ビルドツールが吐く出力**のほう:

- `<script type="module" crossorigin>` と `/assets/<name>-<hash>.js` の絶対パス
- 実行時に取りに行く遅延チャンク (`/heavy`)
- `manifest.json` ではなく **`manifest.webmanifest`**
- Workbox 生成の Service Worker（precache + `navigateFallback`）
- クライアントサイドのルーティング (`/about`)

これで実際に 2 つ見つかっている。`AppAssetRegistry.urlFor` が `/index.html` で
起動していてルーターが 404 を返していた件と、history フォールバックが無く
`/about` での再読み込みが空の 404 になっていた件。

## 生成

`build-samples.py` とは違って **npm が要る**ので、こちらは分けてある。

```
cd samples/real-vite
npm install
VITE_BUILD_ID=BUILD-1 npm run build && (cd dist && zip -qr ../../dist/real-vite-BUILD1.zip .)
VITE_BUILD_ID=BUILD-2 npm run build && (cd dist && zip -qr ../../dist/real-vite-BUILD2.zip .)
```

2 つは同じ manifest id (`real.vite.spa`) でビルド番号だけが違うので、
そのまま更新テストになる。手順と期待値は `samples/TESTING.md`。

`build-samples.py` は最初に `samples/dist/` を消すので、後から走らせるとここの
zip も消える。その場合は上のコマンドをもう一度実行する。

## base をサブパスにしたビルド

`APP_BASE` を渡すと、**同じソースが `/app/` 配下前提の出力になる**。`base` が
変わると `<script src>` も `<link href>` も `registerSW.js` も、manifest の
`start_url` / `scope` も全部 `/app/` を頭に付けて出る。実際のサイトが
`https://example.com/app/` に置かれるときの形で、zip 取り込みが一度も
当てられていない形でもある。

```
APP_BASE=/app/ VITE_BUILD_ID=BASE-1 npm run build
(cd dist && zip -qr ../../dist/real-vite-base-BUILD1.zip .)
mkdir -p /tmp/wrap/app && cp -R dist/. /tmp/wrap/app/
(cd /tmp/wrap && zip -qr ../../dist/real-vite-base-wrapped.zip .)
```

2 つ目は**同じ出力を `app/` フォルダごと包んだ** zip。絶対パスと辻褄が合う
唯一の詰め方に見えるが、`ZipInstaller` の根検出が `app/` を剥がすので結果は
1 つ目と同じになる、というのが確認したいこと。手順と期待値は
`samples/TESTING.md`。

`BrowserRouter` に `basename={import.meta.env.BASE_URL}` を渡してあるのは
この分岐のため。既定 base では `'/'` なので既存のビルドは何も変わらない。

### SW なしの入れ子（切り分け用）

```
APP_BASE=/app/ APP_NO_SW=1 VITE_BUILD_ID=BASE-NOSW npm run build
mkdir -p /tmp/nosw && cp -R dist/. /tmp/nosw/ && rm -f /tmp/nosw/sw.js /tmp/nosw/workbox-*.js
(cd /tmp/nosw && zip -qr ../../dist/real-vite-base-nosw.zip .)
```

`APP_NO_SW=1` は manifest と `base` はそのままに、**Service Worker を登録しない**
（`injectRegister: false`）。id と名前も別にしてあるので、他の 2 本とは別のカードになる。

**深いパスの再読み込みに誰が答えたかを切り分けるための唯一の形。** SW が制御して
いると Workbox の `navigateFallback` が答えてしまい、`WebAppActivity` の
`resolveNavigation`（入れ子のアプリ根の候補）が効いているかどうか分からない。
`basePath` を付けた Next の静的出力が実際にこの形になる。
