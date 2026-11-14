import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearShoppingDataForUser,
  getQueuedShoppingAdds,
  getShoppingSnapshot,
  queueShoppingAdd,
  removeQueuedShoppingAdd,
  saveShoppingSnapshot,
} from './shoppingStore';

const userId = 'offline-user-1';
const circleId = 'circle-offline-1';
const input = { id: 'item-offline-1', text: 'Fresh milk', qty: 2, shopGroup: 'Grocery' };

beforeEach(async () => {
  await clearShoppingDataForUser(userId);
  await clearShoppingDataForUser('offline-user-2');
});

describe('shoppingStore', () => {
  it('partitions saved snapshots by user and circle', async () => {
    await saveShoppingSnapshot({
      userId,
      circleId,
      role: 'HELPER',
      items: [],
      savedAt: '2026-11-14T10:00:00Z',
    });
    await saveShoppingSnapshot({
      userId: 'offline-user-2',
      circleId,
      role: 'VIEWER',
      items: [],
      savedAt: '2026-11-14T10:01:00Z',
    });

    expect(await getShoppingSnapshot(userId, circleId)).toMatchObject({ userId, circleId, role: 'HELPER' });
    expect(await getShoppingSnapshot('offline-user-2', circleId)).toMatchObject({ userId: 'offline-user-2', role: 'VIEWER' });
    expect(await getShoppingSnapshot(userId, 'another-circle')).toBeUndefined();
  });

  it('persists the queue payload and idempotency key until explicitly removed', async () => {
    const queued = await queueShoppingAdd(userId, circleId, input, 'stable-idempotency-key');

    expect(await getQueuedShoppingAdds(userId, circleId)).toEqual([queued]);
    expect(queued.input).toEqual(input);
    expect(queued.idempotencyKey).toBe('stable-idempotency-key');

    await removeQueuedShoppingAdd(queued.queueId);
    expect(await getQueuedShoppingAdds(userId, circleId)).toEqual([]);
  });
});
