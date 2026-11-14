import { openDB, type DBSchema } from 'idb';
import type { MemberRole } from '../api/members';
import type { ListItem, ShoppingItemInput } from '../api/shopping';

export type OfflineShoppingSnapshot = {
  userId: string;
  circleId: string;
  role: MemberRole;
  items: ListItem[];
  savedAt: string;
};

export type QueuedShoppingAdd = {
  queueId: string;
  userId: string;
  circleId: string;
  input: ShoppingItemInput;
  idempotencyKey: string;
  queuedAt: string;
  failureMessage: string | null;
};

interface CareCircleOfflineDb extends DBSchema {
  shoppingSnapshots: {
    key: string;
    value: OfflineShoppingSnapshot & { key: string };
  };
  shoppingQueue: {
    key: string;
    value: QueuedShoppingAdd;
  };
}

const DB_NAME = 'care-circle-offline';
const DB_VERSION = 1;

const database = openDB<CareCircleOfflineDb>(DB_NAME, DB_VERSION, {
  upgrade(db) {
    db.createObjectStore('shoppingSnapshots', { keyPath: 'key' });
    db.createObjectStore('shoppingQueue', { keyPath: 'queueId' });
  },
});

function snapshotKey(userId: string, circleId: string): string {
  return `${userId}:${circleId}`;
}

export async function saveShoppingSnapshot(snapshot: OfflineShoppingSnapshot): Promise<void> {
  const db = await database;
  const key = snapshotKey(snapshot.userId, snapshot.circleId);
  await db.put('shoppingSnapshots', { ...snapshot, key });
}

export async function getShoppingSnapshot(userId: string, circleId: string): Promise<OfflineShoppingSnapshot | undefined> {
  const db = await database;
  const snapshot = await db.get('shoppingSnapshots', snapshotKey(userId, circleId));
  if (!snapshot) return undefined;
  const { key: _key, ...value } = snapshot;
  return value;
}

export async function removeShoppingSnapshot(userId: string, circleId: string): Promise<void> {
  const db = await database;
  await db.delete('shoppingSnapshots', snapshotKey(userId, circleId));
}

export async function queueShoppingAdd(
  userId: string,
  circleId: string,
  input: ShoppingItemInput,
  idempotencyKey: string,
): Promise<QueuedShoppingAdd> {
  const queued: QueuedShoppingAdd = {
    queueId: `${userId}:${circleId}:${input.id}`,
    userId,
    circleId,
    input,
    idempotencyKey,
    queuedAt: new Date().toISOString(),
    failureMessage: null,
  };
  const db = await database;
  await db.put('shoppingQueue', queued);
  return queued;
}

export async function getQueuedShoppingAdds(userId: string, circleId: string): Promise<QueuedShoppingAdd[]> {
  const db = await database;
  return (await db.getAll('shoppingQueue'))
    .filter((entry) => entry.userId === userId && entry.circleId === circleId)
    .sort((left, right) => left.queuedAt.localeCompare(right.queuedAt));
}

export async function removeQueuedShoppingAdd(queueId: string): Promise<void> {
  const db = await database;
  await db.delete('shoppingQueue', queueId);
}

export async function markQueuedShoppingAddFailed(queueId: string, failureMessage: string): Promise<void> {
  const db = await database;
  const entry = await db.get('shoppingQueue', queueId);
  if (entry) await db.put('shoppingQueue', { ...entry, failureMessage });
}

export async function retryQueuedShoppingAdd(queueId: string): Promise<void> {
  const db = await database;
  const entry = await db.get('shoppingQueue', queueId);
  if (entry) await db.put('shoppingQueue', { ...entry, failureMessage: null });
}

export async function clearShoppingDataForUser(userId: string): Promise<void> {
  const db = await database;
  const [snapshots, queued] = await Promise.all([db.getAll('shoppingSnapshots'), db.getAll('shoppingQueue')]);
  const transaction = db.transaction(['shoppingSnapshots', 'shoppingQueue'], 'readwrite');
  for (const snapshot of snapshots.filter((entry) => entry.userId === userId)) {
    void transaction.objectStore('shoppingSnapshots').delete(snapshot.key);
  }
  for (const entry of queued.filter((item) => item.userId === userId)) {
    void transaction.objectStore('shoppingQueue').delete(entry.queueId);
  }
  await transaction.done;
}
