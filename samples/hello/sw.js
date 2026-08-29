const CACHE = 'hello-v1';
const ASSETS = ['index.html', 'manifest.json'];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(self.clients.claim());
});

// Cache-first, which only works if the Service Worker client is wired to the
// same WebViewAssetLoader on the Android side.
self.addEventListener('fetch', event => {
  event.respondWith(
    caches.match(event.request).then(hit => hit || fetch(event.request))
  );
});
