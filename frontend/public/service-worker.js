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
