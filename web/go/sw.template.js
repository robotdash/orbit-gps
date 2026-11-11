// Keeps Orbit working with no signal: the app itself, its voice clips, fonts and map style are
// saved on install. Map pieces are cached by the app (js/regions.js); routes and search need data.
const VERSION = '__VERSION__';
const SHELL = __FILES__;
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open('orbit-app-' + VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k.startsWith('orbit-app-') && k !== 'orbit-app-' + VERSION).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  // Settings: fresh when online.
  if (url.pathname.endsWith('/config.json')) {
    e.respondWith(fetch(e.request).catch(() => caches.match(e.request, { ignoreSearch: true })));
    return;
  }
  e.respondWith(caches.match(e.request, { ignoreSearch: true }).then((hit) => hit || fetch(e.request)));
});
