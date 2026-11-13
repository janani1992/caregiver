import { currentUserId } from './session';

export type PushConfiguration = { publicKey: string | null; deliveryEnabled: boolean };
export type PushSubscriptionRecord = { id: string; endpoint: string; createdAt: string };
export type PushSubscriptions = { subscriptions: PushSubscriptionRecord[] };

async function getError(response: Response): Promise<Error> {
  const result: unknown = await response.json().catch(() => undefined);
  const message =
    typeof result === 'object' && result !== null && 'message' in result && typeof result.message === 'string'
      ? result.message
      : 'Browser notifications could not be configured.';
  return new Error(message);
}

function headers(): HeadersInit {
  return { 'X-User-Id': currentUserId };
}

export async function getPushConfiguration(): Promise<PushConfiguration> {
  const response = await fetch('/v1/push/configuration', { cache: 'no-store' });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<PushConfiguration>;
}

export async function getMyPushSubscriptions(): Promise<PushSubscriptions> {
  const response = await fetch('/v1/push/subscriptions/me', {
    headers: headers(),
    cache: 'no-store',
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<PushSubscriptions>;
}

export async function getCurrentBrowserPushSubscription(): Promise<PushSubscriptionRecord | null> {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return null;
  const registration = await navigator.serviceWorker.ready;
  const browserSubscription = await registration.pushManager.getSubscription();
  if (!browserSubscription) return null;
  const subscriptions = await getMyPushSubscriptions();
  return subscriptions.subscriptions.find((subscription) => subscription.endpoint === browserSubscription.endpoint) ?? null;
}

export async function savePushSubscription(subscription: PushSubscription): Promise<PushSubscriptionRecord> {
  const serialized = subscription.toJSON();
  if (!serialized.endpoint || !serialized.keys?.p256dh || !serialized.keys.auth) {
    throw new Error('The browser returned an incomplete notification subscription.');
  }
  const response = await fetch('/v1/push/subscriptions/me', {
    method: 'POST',
    headers: { ...headers(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ endpoint: serialized.endpoint, keys: serialized.keys }),
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<PushSubscriptionRecord>;
}

export async function removePushSubscription(subscriptionId: string): Promise<void> {
  const response = await fetch(`/v1/push/subscriptions/me/${subscriptionId}`, {
    method: 'DELETE',
    headers: headers(),
  });
  if (!response.ok) throw await getError(response);
}

export function decodeApplicationServerKey(value: string): Uint8Array<ArrayBuffer> {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

export async function enableBrowserNotifications(publicKey: string): Promise<PushSubscriptionRecord> {
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
    throw new Error('Push notifications are not supported by this browser.');
  }
  const permission = Notification.permission === 'granted'
    ? 'granted'
    : await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('Allow notifications in your browser settings to receive urgent alerts.');

  const registration = await navigator.serviceWorker.ready;
  let subscription = await registration.pushManager.getSubscription();
  if (!subscription) {
    subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: decodeApplicationServerKey(publicKey),
    });
  }
  return savePushSubscription(subscription);
}

export async function disableBrowserNotifications(subscriptionId: string): Promise<void> {
  await removePushSubscription(subscriptionId);
  const registration = await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.getSubscription();
  await subscription?.unsubscribe();
}
