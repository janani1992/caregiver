const STATIC_CACHE_PREFIX = 'care-circle-app-shell-';
const STATIC_CACHE_NAME = `${STATIC_CACHE_PREFIX}v1`;
const APP_SHELL_PATH = '/index.html';

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(STATIC_CACHE_NAME);
    const shellResponse = await fetch(APP_SHELL_PATH, { cache: 'reload' });
    if (!shellResponse.ok) throw new Error('Unable to precache the Care Circle app shell');
    const shellHtml = await shellResponse.clone().text();
    await cache.put(APP_SHELL_PATH, shellResponse.clone());
    await cache.put('/', new Response(shellHtml, {
      headers: { 'Content-Type': shellResponse.headers.get('Content-Type') || 'text/html' },
    }));

    const assetPaths = [...shellHtml.matchAll(/(?:src|href)=["']([^"']+)["']/g)]
      .map((match) => match[1])
      .filter((path) => path.startsWith('/assets/'));
    await Promise.all(assetPaths.map(async (path) => {
      const assetResponse = await fetch(path, { cache: 'reload' });
      if (assetResponse.ok) await cache.put(path, assetResponse);
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const cacheNames = await caches.keys();
    await Promise.all(cacheNames
      .filter((name) => name.startsWith(STATIC_CACHE_PREFIX) && name !== STATIC_CACHE_NAME)
      .map((name) => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;

  // API data, including private Care Log notes, is never written to CacheStorage.
  if (url.pathname === '/v1' || url.pathname.startsWith('/v1/')) {
    event.respondWith(fetch(request));
    return;
  }
  if (url.pathname === '/service-worker.js') return;

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).then(async (response) => {
      if (response.ok) {
        const cache = await caches.open(STATIC_CACHE_NAME);
        await cache.put(APP_SHELL_PATH, response.clone());
      }
      return response;
    }).catch(async () => {
      const cachedShell = await caches.match(APP_SHELL_PATH);
      return cachedShell || new Response('Care Circle is unavailable offline on this device.', {
        status: 503,
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      });
    }));
    return;
  }

  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(caches.match(request).then((cached) => cached || fetch(request).then(async (response) => {
      if (response.ok) {
        const cache = await caches.open(STATIC_CACHE_NAME);
        await cache.put(request, response.clone());
      }
      return response;
    })));
  }
});

self.addEventListener('push', (event) => {
  let message = {};
  try {
    message = event.data ? event.data.json() : {};
  } catch {
    message = {};
  }

  const title = typeof message.title === 'string' ? message.title : 'Care Circle';
  const body = typeof message.body === 'string' ? message.body : 'A new urgent request needs attention.';
  const notificationId = typeof message.notificationId === 'string' ? message.notificationId : 'urgent-request';
  const requestedPath = typeof message.url === 'string' ? message.url : '/';
  const targetUrl = requestedPath.startsWith('/') && !requestedPath.startsWith('//')
    ? new URL(requestedPath, self.location.origin).href
    : new URL('/', self.location.origin).href;

  event.waitUntil(self.registration.showNotification(title, {
    body,
    tag: `care-circle-${notificationId}`,
    renotify: false,
    data: { url: targetUrl },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const targetUrl = event.notification.data?.url;
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of windows) {
      if (new URL(client.url).origin !== self.location.origin) continue;
      if ('navigate' in client) await client.navigate(targetUrl);
      await client.focus();
      return;
    }
    await self.clients.openWindow(targetUrl);
  })());
});
