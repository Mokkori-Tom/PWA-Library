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
`/about` での再読み込みが空の 404 になっていた件。詳細は `HANDOVER.md` の「4.」。

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
