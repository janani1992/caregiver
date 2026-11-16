import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearShoppingDataForUser,
  getQueuedShoppingActions,
  getQueuedShoppingAdds,
  getQueuedShoppingOperations,
  getShoppingSnapshot,
  queueShoppingAction,
  queueShoppingAdd,
  removeQueuedShoppingAdd,
  saveShoppingSnapshot,
} from './shoppingStore';

const userId = 'offline-user-1';
const circleId = 'circle-offline-1';
const input = { id: 'item-offline-1', text: 'Fresh milk', qty: 2, shopGroup: 'Grocery' };
const item = {
  id: 'item-action-1',
  circleId,
  text: 'Pain relief',
  qty: null,
  unit: null,
  shopGroup: null,
  status: 'NEEDED' as const,
  claimedBy: null,
  claimedByName: null,
  createdBy: userId,
  createdByName: 'Helper',
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
  version: 3,
};

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

  it('orders queued status actions and retains the expected version and idempotency key', async () => {
    const queuedClaim = await queueShoppingAction(userId, circleId, 'CLAIM', item, 'claim-key');
    const queuedBought = await queueShoppingAction(
      userId,
      circleId,
      'BOUGHT',
      { ...item, status: 'CLAIMED', version: 4, claimedBy: userId, claimedByName: 'Helper' },
      'bought-key',
    );

    expect(await getQueuedShoppingActions(userId, circleId)).toEqual([queuedClaim, queuedBought]);
    expect(await getQueuedShoppingOperations(userId, circleId)).toEqual([queuedClaim, queuedBought]);
    expect(queuedClaim.expectedVersion).toBeNull();
    expect(queuedBought.expectedVersion).toBe(4);
    expect(queuedBought.sequence).toBeGreaterThan(queuedClaim.sequence);
    expect(queuedBought.idempotencyKey).toBe('bought-key');
  });
});
