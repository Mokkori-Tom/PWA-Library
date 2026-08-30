import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// Default base ('/'), so the build emits absolute /assets/... URLs.
//
// APP_BASE=/app/ builds the same app for a subpath deployment instead: every
// emitted URL, the manifest's start_url and scope, and the Workbox fallback all
// move under /app/. That is the shape a project sets when it deploys to
// https://example.com/app/ rather than to a domain root, and it is what the
// zip importer has never been tried against.
const base = process.env.APP_BASE ?? '/'

// APP_NO_SW=1 keeps the manifest and the subpath but never registers the
// Service Worker. That combination — served from a subpath, no worker, a deep
// path reloaded — is the only one in which the native 404 fallback has to
// answer on its own, and it is what a Next export with a basePath looks like.
const registerSw = process.env.APP_NO_SW !== '1'

export default defineConfig({
  base,
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      injectRegister: registerSw ? 'script' : false,
      includeAssets: ['icons/icon-192.png', 'icons/icon-512.png'],
      manifest: {
        id: !registerSw ? 'real.vite.spa.base.nosw'
          : base === '/' ? 'real.vite.spa' : 'real.vite.spa.base',
        name: !registerSw ? '実 PWA テスト (base, SW なし)'
          : base === '/' ? '実 PWA テスト (Vite SPA)' : '実 PWA テスト (base=/app/)',
        short_name: !registerSw ? 'Vite nosw'
          : base === '/' ? 'Vite SPA' : 'Vite base',
        description: 'Vite + React Router + Workbox の実ビルド出力',
        start_url: base,
        scope: base,
        display: 'standalone',
        theme_color: '#2b6cb0',
        background_color: '#ffffff',
        icons: [
          { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // Workbox's default: every navigation is answered from the precached
        // index.html. This is the SW shape the reset path has to break.
        navigateFallback: `${base}index.html`,
        globPatterns: ['**/*.{js,css,html,png,svg,woff2}'],
      },
    }),
  ],
})
