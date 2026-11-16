import { openDB } from 'idb';
import { describe, expect, it } from 'vitest';

describe('shoppingStore schema migration', () => {
  it('migrates existing queued adds into the ordered operation queue', async () => {
    const databaseName = 'care-circle-offline';
    const deleteRequest = indexedDB.deleteDatabase(databaseName);
    await new Promise<void>((resolve, reject) => {
      deleteRequest.onsuccess = () => resolve();
      deleteRequest.onerror = () => reject(deleteRequest.error);
      deleteRequest.onblocked = () => reject(new Error('The offline database could not be reset for the migration test.'));
    });

    const legacyDatabase = await openDB(databaseName, 1, {
      upgrade(database) {
        database.createObjectStore('shoppingSnapshots', { keyPath: 'key' });
        database.createObjectStore('shoppingQueue', { keyPath: 'queueId' });
      },
    });
    await legacyDatabase.put('shoppingQueue', {
      queueId: 'offline-user-1:circle-offline-1:item-offline-1',
      userId: 'offline-user-1',
      circleId: 'circle-offline-1',
      input: { id: 'item-offline-1', text: 'Fresh milk' },
      idempotencyKey: 'legacy-idempotency-key',
      queuedAt: '2026-11-14T10:00:00Z',
      failureMessage: null,
    });
    await legacyDatabase.close();

    const store = await import('./shoppingStore');
    const migrated = await store.getQueuedShoppingOperations('offline-user-1', 'circle-offline-1');

    expect(migrated).toHaveLength(1);
    expect(migrated[0]).toMatchObject({
      operation: 'ADD',
      sequence: 1,
      idempotencyKey: 'legacy-idempotency-key',
      input: { id: 'item-offline-1', text: 'Fresh milk' },
    });
  });
});
