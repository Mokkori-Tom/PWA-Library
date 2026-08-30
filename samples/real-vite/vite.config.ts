import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// Default base ('/'), so the build emits absolute /assets/... URLs.
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icons/icon-192.png', 'icons/icon-512.png'],
      manifest: {
        id: 'real.vite.spa',
        name: '実 PWA テスト (Vite SPA)',
        short_name: 'Vite SPA',
        description: 'Vite + React Router + Workbox の実ビルド出力',
        start_url: '/',
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
        navigateFallback: '/index.html',
        globPatterns: ['**/*.{js,css,html,png,svg,woff2}'],
      },
    }),
  ],
})
