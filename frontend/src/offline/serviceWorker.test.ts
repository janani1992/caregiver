import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { runInNewContext } from 'node:vm';
import { beforeAll, describe, expect, it, vi } from 'vitest';

const origin = 'https://care-circle.test';
let workerSource = '';

beforeAll(async () => {
  workerSource = await readFile(join(process.cwd(), 'public', 'service-worker.js'), 'utf8');
});

function createWorkerHarness() {
  const handlers = new Map<string, (event: any) => void>();
  const storedResponses = new Map<string, Response>();
  const cacheNames = new Set<string>();
  const cache = {
    match: vi.fn(async (request: string | Request) => storedResponses.get(cacheKey(request))),
    put: vi.fn(async (request: string | Request, response: Response) => {
      storedResponses.set(cacheKey(request), response.clone());
    }),
  };
  function cacheKey(request: string | Request): string {
    const url = typeof request === 'string' ? new URL(request, origin) : new URL(request.url);
    return `${url.pathname}${url.search}`;
  }
  const cachesApi = {
    open: vi.fn(async (name: string) => {
      cacheNames.add(name);
      return cache;
    }),
    match: vi.fn(async (request: string | Request) => storedResponses.get(cacheKey(request))),
    keys: vi.fn(async () => [...cacheNames]),
    delete: vi.fn(async (name: string) => cacheNames.delete(name)),
  };
  const selfApi = {
    location: { origin },
    registration: { showNotification: vi.fn(async () => undefined) },
    clients: { claim: vi.fn(async () => undefined) },
    skipWaiting: vi.fn(async () => undefined),
    addEventListener: (type: string, handler: (event: any) => void) => handlers.set(type, handler),
  };
  const html = '<html><head><link rel="stylesheet" href="/assets/app.css"></head><body><script src="/assets/app.js"></script></body></html>';
  const networkFetch = vi.fn(async (request: string | Request) => {
    const path = typeof request === 'string' ? request : new URL(request.url).pathname;
    if (path === '/index.html') return new Response(html, { headers: { 'Content-Type': 'text/html' } });
    return new Response(`asset:${path}`, { status: 200 });
  });

  runInNewContext(workerSource, {
    self: selfApi,
    caches: cachesApi,
    fetch: networkFetch,
    URL,
    Response,
    Promise,
  });

  return { handlers, cache, cacheNames, cachesApi, networkFetch, selfApi };
}

async function installWorker(handlers: Map<string, (event: any) => void>) {
  let installation: Promise<void> | undefined;
  handlers.get('install')?.({ waitUntil: (promise: Promise<void>) => { installation = promise; } });
  await installation;
}

describe('service worker offline policy', () => {
  it('pre-caches the app shell and same-origin build assets', async () => {
    const { handlers, cache } = createWorkerHarness();
    await installWorker(handlers);

    expect(cache.put).toHaveBeenCalledWith('/index.html', expect.any(Response));
    expect(cache.put).toHaveBeenCalledWith('/', expect.any(Response));
    expect(cache.put).toHaveBeenCalledWith('/assets/app.js', expect.any(Response));
    expect(cache.put).toHaveBeenCalledWith('/assets/app.css', expect.any(Response));
  });

  it('never caches API responses and serves the saved shell for offline route navigation', async () => {
    const { handlers, cache, networkFetch } = createWorkerHarness();
    await installWorker(handlers);
    const putsAfterInstall = cache.put.mock.calls.length;
    let apiResponse: Promise<Response> | undefined;
    handlers.get('fetch')?.({
      request: { url: `${origin}/v1/circles/circle-1/items`, method: 'GET', mode: 'cors' },
      respondWith: (response: Promise<Response>) => { apiResponse = response; },
    });
    expect((await apiResponse)?.status).toBe(200);
    expect(cache.put).toHaveBeenCalledTimes(putsAfterInstall);

    networkFetch.mockRejectedValueOnce(new TypeError('offline'));
    let navigationResponse: Promise<Response> | undefined;
    handlers.get('fetch')?.({
      request: { url: `${origin}/circles/circle-1`, method: 'GET', mode: 'navigate' },
      respondWith: (response: Promise<Response>) => { navigationResponse = response; },
    });
    expect(await (await navigationResponse)?.text()).toContain('<html>');
  });

  it('removes obsolete application-shell caches on activation', async () => {
    const { handlers, cacheNames, cachesApi } = createWorkerHarness();
    cacheNames.add('care-circle-app-shell-old');
    await installWorker(handlers);
    let activation: Promise<void> | undefined;
    handlers.get('activate')?.({ waitUntil: (promise: Promise<void>) => { activation = promise; } });
    await activation;

    expect(cachesApi.delete).toHaveBeenCalledWith('care-circle-app-shell-old');
  });

  it('keeps the existing generic push notification behavior', async () => {
    const { handlers, selfApi } = createWorkerHarness();
    let delivery: Promise<void> | undefined;
    handlers.get('push')?.({
      data: { json: () => ({ title: 'Care Circle', body: 'A new urgent request needs attention.', notificationId: 'notice-1', url: '/circles/circle-1' }) },
      waitUntil: (promise: Promise<void>) => { delivery = promise; },
    });
    await delivery;

    expect(selfApi.registration.showNotification).toHaveBeenCalledWith('Care Circle', expect.objectContaining({
      body: 'A new urgent request needs attention.',
      tag: 'care-circle-notice-1',
    }));
  });
});
