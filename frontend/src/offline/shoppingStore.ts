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

type QueuedShoppingBase = {
  queueId: string;
  userId: string;
  circleId: string;
  idempotencyKey: string;
  queuedAt: string;
  sequence: number;
  failureMessage: string | null;
};

export type QueuedShoppingAdd = QueuedShoppingBase & {
  operation: 'ADD';
  input: ShoppingItemInput;
};

export type QueuedShoppingAction = QueuedShoppingBase & {
  operation: 'CLAIM' | 'BOUGHT';
  itemId: string;
  expectedVersion: number | null;
  itemSnapshot: ListItem;
};

export type QueuedShoppingOperation = QueuedShoppingAdd | QueuedShoppingAction;
type NewQueuedShoppingOperation = Omit<QueuedShoppingAdd, 'sequence'> | Omit<QueuedShoppingAction, 'sequence'>;

interface CareCircleOfflineDb extends DBSchema {
  shoppingSnapshots: {
    key: string;
    value: OfflineShoppingSnapshot & { key: string };
  };
  shoppingQueue: {
    key: string;
    value: QueuedShoppingOperation;
  };
  shoppingQueueCounters: {
    key: string;
    value: { key: string; nextSequence: number };
  };
}

const DB_NAME = 'care-circle-offline';
const DB_VERSION = 2;

const database = openDB<CareCircleOfflineDb>(DB_NAME, DB_VERSION, {
  upgrade(db, oldVersion, _newVersion, transaction) {
    if (oldVersion < 1) {
      db.createObjectStore('shoppingSnapshots', { keyPath: 'key' });
      db.createObjectStore('shoppingQueue', { keyPath: 'queueId' });
    }
    if (oldVersion < 2) {
      const counters = db.createObjectStore('shoppingQueueCounters', { keyPath: 'key' });
      const queue = transaction.objectStore('shoppingQueue');
      void queue.getAll().then((entries) => {
        entries.sort((left, right) => left.queuedAt.localeCompare(right.queuedAt));
        entries.forEach((entry, index) => {
          queue.put({ ...entry, operation: entry.operation ?? 'ADD', sequence: index + 1 } as QueuedShoppingOperation);
        });
        counters.put({ key: 'sequence', nextSequence: entries.length });
      });
    }
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
  return putQueuedOperation({
    queueId: `${userId}:${circleId}:${input.id}`,
    userId,
    circleId,
    operation: 'ADD',
    input,
    idempotencyKey,
    queuedAt: new Date().toISOString(),
    failureMessage: null,
  }) as Promise<QueuedShoppingAdd>;
}

export async function queueShoppingAction(
  userId: string,
  circleId: string,
  operation: QueuedShoppingAction['operation'],
  itemSnapshot: ListItem,
  idempotencyKey: string,
): Promise<QueuedShoppingAction> {
  const expectedVersion = operation === 'BOUGHT' ? itemSnapshot.version : null;
  return putQueuedOperation({
    queueId: `${userId}:${circleId}:${operation}:${itemSnapshot.id}:${idempotencyKey}`,
    userId,
    circleId,
    operation,
    itemId: itemSnapshot.id,
    expectedVersion,
    itemSnapshot,
    idempotencyKey,
    queuedAt: new Date().toISOString(),
    failureMessage: null,
  }) as Promise<QueuedShoppingAction>;
}

async function putQueuedOperation(
  operation: NewQueuedShoppingOperation,
): Promise<QueuedShoppingOperation> {
  const db = await database;
  const transaction = db.transaction(['shoppingQueue', 'shoppingQueueCounters'], 'readwrite');
  const counterStore = transaction.objectStore('shoppingQueueCounters');
  const counter = await counterStore.get('sequence');
  const queued = { ...operation, sequence: (counter?.nextSequence ?? 0) + 1 } as QueuedShoppingOperation;
  await counterStore.put({ key: 'sequence', nextSequence: queued.sequence });
  await transaction.objectStore('shoppingQueue').put(queued);
  await transaction.done;
  return queued;
}

export async function getQueuedShoppingAdds(userId: string, circleId: string): Promise<QueuedShoppingAdd[]> {
  return (await getQueuedShoppingOperations(userId, circleId))
    .filter((entry): entry is QueuedShoppingAdd => entry.operation === 'ADD');
}

export async function getQueuedShoppingActions(userId: string, circleId: string): Promise<QueuedShoppingAction[]> {
  return (await getQueuedShoppingOperations(userId, circleId))
    .filter((entry): entry is QueuedShoppingAction => entry.operation === 'CLAIM' || entry.operation === 'BOUGHT');
}

export async function getQueuedShoppingOperations(userId: string, circleId: string): Promise<QueuedShoppingOperation[]> {
  const db = await database;
  return (await db.getAll('shoppingQueue'))
    .filter((entry) => entry.userId === userId && entry.circleId === circleId)
    .map((entry) => {
      const legacyEntry = entry as unknown as Record<string, unknown> & {
        operation?: QueuedShoppingOperation['operation'];
        sequence?: number;
      };
      if (legacyEntry.operation) return legacyEntry as unknown as QueuedShoppingOperation;
      return { ...legacyEntry, operation: 'ADD', sequence: legacyEntry.sequence ?? 0 } as unknown as QueuedShoppingAdd;
    })
    .sort((left, right) => left.sequence - right.sequence || left.queuedAt.localeCompare(right.queuedAt));
}

export async function removeQueuedShoppingAdd(queueId: string): Promise<void> {
  return removeQueuedShoppingOperation(queueId);
}

export async function removeQueuedShoppingOperation(queueId: string): Promise<void> {
  const db = await database;
  await db.delete('shoppingQueue', queueId);
}

export async function removeQueuedShoppingActionsForItem(userId: string, circleId: string, itemId: string): Promise<void> {
  const db = await database;
  const actions = await getQueuedShoppingActions(userId, circleId);
  const transaction = db.transaction('shoppingQueue', 'readwrite');
  for (const action of actions.filter((entry) => entry.itemId === itemId)) {
    void transaction.store.delete(action.queueId);
  }
  await transaction.done;
}

export async function markQueuedShoppingAddFailed(queueId: string, failureMessage: string): Promise<void> {
  return markQueuedShoppingOperationFailed(queueId, failureMessage);
}

export async function markQueuedShoppingOperationFailed(queueId: string, failureMessage: string): Promise<void> {
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
