import { afterEach, describe, expect, it, vi } from 'vitest';
import { decodeApplicationServerKey, enableBrowserNotifications } from './push';

describe('push subscription helpers', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('decodes an application server key from base64url', () => {
    expect(Array.from(decodeApplicationServerKey('AQIDBA'))).toEqual([1, 2, 3, 4]);
    expect(Array.from(decodeApplicationServerKey('AQIDBA=='))).toEqual([1, 2, 3, 4]);
  });

  it('fails gracefully when the browser lacks push support', async () => {
    await expect(enableBrowserNotifications('AQIDBA')).rejects.toThrow('Push notifications are not supported');
  });

  it('does not subscribe when the user has denied notification permission', async () => {
    const requestPermission = vi.fn().mockResolvedValue('denied');
    vi.stubGlobal('PushManager', class PushManager {});
    vi.stubGlobal('Notification', { permission: 'denied', requestPermission });
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: { ready: Promise.resolve({ pushManager: { subscribe: vi.fn() } }) },
    });

    await expect(enableBrowserNotifications('AQIDBA')).rejects.toThrow('Allow notifications');
    expect(requestPermission).toHaveBeenCalledOnce();
  });
});
