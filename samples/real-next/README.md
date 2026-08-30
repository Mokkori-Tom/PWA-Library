# 実 PWA テスト (Next 静的出力)

`output: "export"` の素の出力。Vite の SPA (`samples/real-vite/`) との違いは
**ルートごとに HTML が出る**ところで、ここが container の作りと噛み合うかを見る。

- `/about` は `out/about.html`。`out/about/index.html` **ではない**
- クライアント遷移は `about.txt`（RSC ペイロード）を取りに行く
- アセットは `/_next/static/chunks/<hash>.js`
- manifest は `/manifest.webmanifest`（`app/manifest.ts` から生成。静的出力では
  `export const dynamic = "force-static"` を付けないとビルドが通らない）
- **Service Worker は無い**。Next は吐かないので、history フォールバックの
  肩代わりをしてくれるものが無い

## 生成

npm が要るので `build-samples.py` からは外してある。

```
cd samples/real-next
npm install
NEXT_PUBLIC_BUILD_ID=BUILD-1 npm run build && (cd out && zip -qr ../../dist/real-next-BUILD1.zip .)
NEXT_PUBLIC_BUILD_ID=BUILD-2 npm run build && (cd out && zip -qr ../../dist/real-next-BUILD2.zip .)
```

同じ manifest id (`real.next.export`) なので、2 つ続けて取り込めば更新テストになる。
`build-samples.py` は `samples/dist/` を消すので、後から走らせたら作り直す。
