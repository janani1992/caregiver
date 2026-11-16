import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getCircleMembers, type MemberRole } from '../../api/members';
import { currentUserId } from '../../api/session';
import {
  addShoppingItem,
  canDeleteShoppingItem,
  canWriteShoppingList,
  claimShoppingItem,
  getShoppingItems,
  isShoppingAccessRevoked,
  isRetryableShoppingError,
  markShoppingItemBought,
  removeShoppingItem,
  type ListItem,
  type ListItemStatus,
} from '../../api/shopping';
import {
  getQueuedShoppingAdds,
  getQueuedShoppingActions,
  getQueuedShoppingOperations,
  getShoppingSnapshot,
  markQueuedShoppingAddFailed,
  markQueuedShoppingOperationFailed,
  queueShoppingAdd,
  queueShoppingAction,
  removeQueuedShoppingAdd,
  removeQueuedShoppingActionsForItem,
  removeShoppingSnapshot,
  retryQueuedShoppingAdd,
  saveShoppingSnapshot,
  type QueuedShoppingAction,
  type QueuedShoppingAdd,
} from '../../offline/shoppingStore';

type Filter = 'ALL' | ListItemStatus;

const statusLabels: Record<ListItemStatus, string> = {
  NEEDED: 'Needed',
  CLAIMED: 'Picked up',
  BOUGHT: 'Bought',
  NOT_FOUND: 'Not found',
};

export default function ShoppingListPanel({ circleId }: { circleId: string }) {
  const [items, setItems] = useState<ListItem[]>([]);
  const [role, setRole] = useState<MemberRole | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [text, setText] = useState('');
  const [qty, setQty] = useState('');
  const [shopGroup, setShopGroup] = useState('');
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [queuedAdds, setQueuedAdds] = useState<QueuedShoppingAdd[]>([]);
  const [queuedActions, setQueuedActions] = useState<QueuedShoppingAction[]>([]);
  const [hasSavedSnapshot, setHasSavedSnapshot] = useState(false);
  const [snapshotSavedAt, setSnapshotSavedAt] = useState<string | null>(null);
  const [networkUnavailable, setNetworkUnavailable] = useState(false);
  const [dataUnavailableOffline, setDataUnavailableOffline] = useState(false);
  const hasSavedSnapshotRef = useRef(false);

  const refreshQueue = useCallback(async () => {
    const [adds, actions] = await Promise.all([
      getQueuedShoppingAdds(currentUserId, circleId),
      getQueuedShoppingActions(currentUserId, circleId),
    ]);
    setQueuedAdds(adds);
    setQueuedActions(actions);
  }, [circleId]);

  const reload = useCallback(async () => {
    setError('');
    const savedSnapshot = await getShoppingSnapshot(currentUserId, circleId).catch(() => undefined);
    if (savedSnapshot) {
      setItems(savedSnapshot.items);
      setRole(savedSnapshot.role);
      hasSavedSnapshotRef.current = true;
      setHasSavedSnapshot(true);
      setSnapshotSavedAt(savedSnapshot.savedAt);
    } else {
      setItems([]);
      setRole(null);
      hasSavedSnapshotRef.current = false;
      setHasSavedSnapshot(false);
      setSnapshotSavedAt(null);
    }
    await refreshQueue().catch(() => undefined);
    if (!navigator.onLine) {
      setNetworkUnavailable(true);
      setDataUnavailableOffline(!savedSnapshot);
      setLoading(false);
      return;
    }
    try {
      const [list, roster] = await Promise.all([getShoppingItems(circleId), getCircleMembers(circleId)]);
      setItems(list.items);
      setRole(roster.currentUserRole);
      setNetworkUnavailable(false);
      setDataUnavailableOffline(false);
      const savedAt = new Date().toISOString();
      const snapshotSaved = await saveShoppingSnapshot({
        userId: currentUserId,
        circleId,
        role: roster.currentUserRole,
        items: list.items,
        savedAt,
      }).then(() => true).catch(() => false);
      const hasSnapshot = snapshotSaved || !!savedSnapshot;
      hasSavedSnapshotRef.current = hasSnapshot;
      setHasSavedSnapshot(hasSnapshot);
      if (snapshotSaved) setSnapshotSavedAt(savedAt);
    } catch (reason) {
      if (isRetryableShoppingError(reason)) {
        setNetworkUnavailable(true);
        setDataUnavailableOffline(!savedSnapshot);
        if (!savedSnapshot) setError(reason instanceof Error ? reason.message : 'The shopping list could not be loaded.');
      } else if (isShoppingAccessRevoked(reason)) {
        await removeShoppingSnapshot(currentUserId, circleId).catch(() => undefined);
        const queued = await getQueuedShoppingAdds(currentUserId, circleId).catch(() => []);
        const queuedActions = await getQueuedShoppingActions(currentUserId, circleId).catch(() => []);
        await Promise.all(queued.map((entry) => markQueuedShoppingAddFailed(entry.queueId, 'Circle access changed. Discard or contact a circle owner.')));
        await Promise.all(queuedActions.map((entry) => markQueuedShoppingOperationFailed(entry.queueId, 'Circle access changed. Discard or contact a circle owner.')));
        await refreshQueue().catch(() => undefined);
        setItems([]);
        setRole(null);
        hasSavedSnapshotRef.current = false;
        setHasSavedSnapshot(false);
        setSnapshotSavedAt(null);
        setNetworkUnavailable(false);
        setDataUnavailableOffline(false);
        setError(reason instanceof Error ? reason.message : 'The shopping list could not be loaded.');
      } else {
        setDataUnavailableOffline(!savedSnapshot);
        setError(reason instanceof Error ? reason.message : 'The shopping list could not be loaded.');
      }
    } finally {
      setLoading(false);
    }
  }, [circleId, refreshQueue]);

  const syncQueuedOperations = useCallback(async () => {
    if (!navigator.onLine) return;
    const queued = await getQueuedShoppingOperations(currentUserId, circleId).catch(() => []);
    let syncedAny = false;
    let accessRejected = false;
    const blockedItemIds = new Set<string>();
    for (const operation of queued) {
      const affectedItemId = operation.operation === 'ADD' ? operation.input.id : operation.itemId;
      if (operation.failureMessage) {
        blockedItemIds.add(affectedItemId);
        continue;
      }
      if (blockedItemIds.has(affectedItemId)) continue;
      try {
        if (operation.operation === 'ADD') {
          await addShoppingItem(circleId, operation.input, operation.idempotencyKey);
        } else if (operation.operation === 'CLAIM') {
          await claimShoppingItem(operation.itemId, operation.idempotencyKey);
        } else {
          const item = { ...operation.itemSnapshot, version: operation.expectedVersion ?? operation.itemSnapshot.version };
          await markShoppingItemBought(item, operation.idempotencyKey);
        }
        await removeQueuedShoppingAdd(operation.queueId);
        syncedAny = true;
      } catch (reason) {
        if (isRetryableShoppingError(reason)) {
          setNetworkUnavailable(true);
          break;
        }
        const message = reason instanceof Error ? reason.message : 'This item could not be synced.';
        await markQueuedShoppingOperationFailed(operation.queueId, message);
        blockedItemIds.add(affectedItemId);
        if (isShoppingAccessRevoked(reason)) accessRejected = true;
      }
    }
    await refreshQueue().catch(() => undefined);
    if (syncedAny || accessRejected) await reload();
  }, [circleId, refreshQueue, reload]);

  useEffect(() => {
    const handleOffline = () => {
      setNetworkUnavailable(true);
      setDataUnavailableOffline(!hasSavedSnapshotRef.current);
    };
    const handleOnline = () => { void (async () => { await syncQueuedOperations(); await reload(); })(); };
    const handleFocus = () => { void syncQueuedOperations(); };
    window.addEventListener('offline', handleOffline);
    window.addEventListener('online', handleOnline);
    window.addEventListener('focus', handleFocus);
    void (async () => { await reload(); await syncQueuedOperations(); })();
    return () => {
      window.removeEventListener('offline', handleOffline);
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('focus', handleFocus);
    };
  }, [reload, syncQueuedOperations]);

  function toPendingItem(queuedAdd: QueuedShoppingAdd): ListItem {
    const timestamp = queuedAdd.queuedAt;
    return {
      id: queuedAdd.input.id,
      circleId,
      text: queuedAdd.input.text,
      qty: queuedAdd.input.qty ?? null,
      unit: null,
      shopGroup: queuedAdd.input.shopGroup ?? null,
      status: 'NEEDED',
      claimedBy: null,
      claimedByName: null,
      createdBy: currentUserId,
      createdByName: 'You',
      createdAt: timestamp,
      updatedAt: timestamp,
      version: 1,
    };
  }

  const pendingIds = useMemo(() => new Set(queuedAdds.map(({ input }) => input.id)), [queuedAdds]);
  const pendingActionIds = useMemo(() => new Set(queuedActions.map((action) => action.itemId)), [queuedActions]);
  const failedOperationByItem = useMemo(() => {
    const failures = new Map<string, string>();
    for (const action of queuedActions) {
      if (action.failureMessage && !failures.has(action.itemId)) failures.set(action.itemId, action.failureMessage);
    }
    return failures;
  }, [queuedActions]);
  const displayedItems = useMemo(() => {
    const itemById = new Map(items.map((item) => [item.id, item]));
    const blockedItemIds = new Set<string>();
    for (const action of queuedActions) {
      if (!itemById.has(action.itemId)) itemById.set(action.itemId, action.itemSnapshot);
      if (action.failureMessage) {
        blockedItemIds.add(action.itemId);
        continue;
      }
      if (blockedItemIds.has(action.itemId)) continue;

      const current = itemById.get(action.itemId);
      if (!current) continue;
      if (action.operation === 'CLAIM' && current.status === 'NEEDED') {
        itemById.set(action.itemId, {
          ...current,
          status: 'CLAIMED',
          claimedBy: currentUserId,
          claimedByName: 'You',
          version: current.version + 1,
        });
      } else if (action.operation === 'BOUGHT' && current.status === 'CLAIMED') {
        itemById.set(action.itemId, { ...current, status: 'BOUGHT', version: current.version + 1 });
      }
    }

    const knownIds = new Set(itemById.keys());
    return [...queuedAdds.map(toPendingItem).filter((item) => !knownIds.has(item.id)), ...itemById.values()];
  }, [circleId, items, queuedActions, queuedAdds]);

  const visibleItems = useMemo(
    () => filter === 'ALL' ? displayedItems : displayedItems.filter((item) => item.status === filter),
    [displayedItems, filter],
  );

  async function discardPendingItem(itemId: string) {
    const queuedAdd = queuedAdds.find((entry) => entry.input.id === itemId);
    if (queuedAdd) await removeQueuedShoppingAdd(queuedAdd.queueId);
    await removeQueuedShoppingActionsForItem(currentUserId, circleId, itemId);
    await refreshQueue();
  }

  async function retryQueuedAdd(queuedAdd: QueuedShoppingAdd) {
    await retryQueuedShoppingAdd(queuedAdd.queueId);
    await refreshQueue();
    await syncQueuedOperations();
  }

  async function enqueueAction(item: ListItem, action: 'claim' | 'bought', idempotencyKey: string) {
    await queueShoppingAction(
      currentUserId,
      circleId,
      action === 'claim' ? 'CLAIM' : 'BOUGHT',
      item,
      idempotencyKey,
    );
    await refreshQueue();
    setNetworkUnavailable(true);
  }

  async function submitItem(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanText = text.trim();
    if (!cleanText || saving) return;
    setSaving(true);
    setError('');
    const input = {
      id: crypto.randomUUID(),
      text: cleanText,
      ...(qty.trim() && Number(qty) ? { qty: Number(qty) } : {}),
      ...(shopGroup.trim() ? { shopGroup: shopGroup.trim() } : {}),
    };
    const idempotencyKey = crypto.randomUUID();
    try {
      if (!navigator.onLine || networkUnavailable) {
        await queueShoppingAdd(currentUserId, circleId, input, idempotencyKey);
        await refreshQueue();
        setNetworkUnavailable(true);
        setDataUnavailableOffline(!hasSavedSnapshot);
      } else {
        try {
          await addShoppingItem(circleId, input, idempotencyKey);
        } catch (reason) {
          if (!isRetryableShoppingError(reason)) throw reason;
          await queueShoppingAdd(currentUserId, circleId, input, idempotencyKey);
          await refreshQueue();
          setNetworkUnavailable(true);
          setDataUnavailableOffline(!hasSavedSnapshot);
        }
      }
      setText('');
      setQty('');
      setShopGroup('');
      if (!networkUnavailable && navigator.onLine) await reload();
    } catch (reason) {
      if (isRetryableShoppingError(reason)) setNetworkUnavailable(true);
      setError(reason instanceof Error ? reason.message : 'The item could not be added.');
    } finally {
      setSaving(false);
    }
  }

  async function runItemAction(item: ListItem, action: 'claim' | 'bought' | 'remove') {
    setBusyId(item.id);
    setError('');
    const idempotencyKey = crypto.randomUUID();
    try {
      if (action === 'remove') {
        if (!navigator.onLine || networkUnavailable) {
          setError('Removing items still requires a connection.');
          return;
        }
        await removeShoppingItem(item.id);
        await reload();
        return;
      }

      if (!navigator.onLine || networkUnavailable) {
        await enqueueAction(item, action, idempotencyKey);
        return;
      }

      try {
        if (action === 'claim') await claimShoppingItem(item.id, idempotencyKey);
        else await markShoppingItemBought(item, idempotencyKey);
        await reload();
      } catch (reason) {
        if (!isRetryableShoppingError(reason)) throw reason;
        await enqueueAction(item, action, idempotencyKey);
      }
    } catch (reason) {
      if (isRetryableShoppingError(reason)) {
        setNetworkUnavailable(true);
        setError(reason instanceof Error ? reason.message : 'The item could not be updated.');
      } else {
        await reload();
        setError(reason instanceof Error ? reason.message : 'The item could not be updated.');
      }
    } finally {
      setBusyId(null);
    }
  }

  return (
    <section className="shopping-card" aria-labelledby="shopping-title">
      <div className="shopping-heading">
        <div>
          <p className="eyebrow">A LITTLE HELP, SHARED</p>
          <h2 id="shopping-title">Shopping list</h2>
          <p className="muted">Keep everyday essentials in one place for everyone in your circle.</p>
        </div>
        <span className="shopping-icon" aria-hidden="true">＋</span>
      </div>

      {networkUnavailable && (
        <p className="offline-banner" role="status">
          {!navigator.onLine ? 'You’re offline.' : 'The server can’t be reached.'}{' '}
          {hasSavedSnapshot
            ? `Showing this device’s saved list${snapshotSavedAt ? ` from ${new Date(snapshotSavedAt).toLocaleString()}` : ''}. New items will sync when the connection returns.`
            : 'No saved list is available on this device yet; queued items will appear below.'}
        </p>
      )}
      {error && <p className="form-error shopping-error" role="alert">{error}</p>}
      {role && canWriteShoppingList(role) && (
        <form className="shopping-form" onSubmit={(event) => void submitItem(event)}>
          <label htmlFor="shopping-item-text">Add an item</label>
          <div className="shopping-form-row">
            <input
              id="shopping-item-text"
              value={text}
              maxLength={200}
              onChange={(event) => setText(event.target.value)}
              placeholder="e.g. Fresh milk"
              required
            />
            <input
              aria-label="Quantity"
              type="number"
              min="1"
              max="999"
              value={qty}
              onChange={(event) => setQty(event.target.value)}
              placeholder="Qty"
            />
            <input
              aria-label="Shop or category"
              value={shopGroup}
              maxLength={64}
              onChange={(event) => setShopGroup(event.target.value)}
              placeholder="Shop"
            />
            <button type="submit" disabled={saving || !text.trim()}>{saving ? 'Adding…' : 'Add item'}</button>
          </div>
        </form>
      )}

      <div className="shopping-filters" aria-label="Filter shopping items">
        {(['ALL', 'NEEDED', 'CLAIMED', 'BOUGHT', 'NOT_FOUND'] as const).map((value) => (
          <button
            type="button"
            className={filter === value ? 'shopping-filter active' : 'shopping-filter'}
            aria-pressed={filter === value}
            key={value}
            onClick={() => setFilter(value)}
          >
            {value === 'ALL' ? 'All' : statusLabels[value]}
          </button>
        ))}
      </div>

      {loading ? (
        <p className="shopping-empty" role="status">Loading the shared list…</p>
      ) : dataUnavailableOffline && displayedItems.length === 0 ? (
        <div className="shopping-empty" role="status">
          <span aria-hidden="true">⌁</span>
          <p>{navigator.onLine ? 'The list is unavailable. Check your connection and circle access.' : 'Connect once to save this circle’s shopping list for offline use.'}</p>
        </div>
      ) : visibleItems.length === 0 ? (
        <div className="shopping-empty">
          <span aria-hidden="true">✳</span>
          <p>{displayedItems.length === 0 ? 'Your list is ready when you are.' : 'No items in this section yet.'}</p>
        </div>
      ) : (
        <ul className="shopping-items">
          {visibleItems.map((item) => {
            const queuedAdd = queuedAdds.find(({ input }) => input.id === item.id);
            const failedMessage = queuedAdd?.failureMessage ?? failedOperationByItem.get(item.id);
            const hasPendingMutation = pendingIds.has(item.id) || pendingActionIds.has(item.id);
            const hasConflict = Boolean(failedMessage);
            const canQueueStatus = role !== null && canWriteShoppingList(role) && !hasConflict;
            return (
              <li className="shopping-item" key={item.id}>
                <span className={`item-status-dot status-${item.status.toLowerCase()}`} aria-hidden="true" />
                <div className="shopping-item-details">
                  <strong>{item.text}{item.qty != null && <span className="item-quantity"> · {item.qty}{item.unit ? ` ${item.unit}` : ''}</span>}</strong>
                  <span>
                    {item.shopGroup ? `${item.shopGroup} · ` : ''}
                    {item.status === 'CLAIMED' && item.claimedByName ? `Picked up by ${item.claimedByName}` : `Added by ${item.createdByName}`}
                  </span>
                  {failedMessage && <span className="queued-item-error" role="status">{failedMessage}</span>}
                </div>
                {hasPendingMutation ? (
                  <span className="item-status-label item-status-pending">{hasConflict ? 'Needs attention' : 'Waiting to sync'}</span>
                ) : <span className={`item-status-label status-label-${item.status.toLowerCase()}`}>{statusLabels[item.status]}</span>}
                {hasPendingMutation && (
                  <>
                    {queuedAdd?.failureMessage && (
                      <button className="item-action-button" type="button" onClick={() => void retryQueuedAdd(queuedAdd)}>
                        Retry
                      </button>
                    )}
                    <button className="item-remove-button" type="button" aria-label={`Discard pending changes for ${item.text}`} onClick={() => void discardPendingItem(item.id)}>
                      Discard
                    </button>
                  </>
                )}
                {canQueueStatus && item.status === 'NEEDED' && !pendingIds.has(item.id) && (
                  <button className="item-action-button" type="button" disabled={busyId === item.id} onClick={() => void runItemAction(item, 'claim')}>
                    {busyId === item.id ? '…' : navigator.onLine && !networkUnavailable ? 'I’ll get it' : 'Queue pickup'}
                  </button>
                )}
                {canQueueStatus && item.status === 'CLAIMED' && (
                  <button className="item-action-button" type="button" disabled={busyId === item.id} onClick={() => void runItemAction(item, 'bought')}>
                    {busyId === item.id ? '…' : navigator.onLine && !networkUnavailable ? 'Mark bought' : 'Queue as bought'}
                  </button>
                )}
                {role && canDeleteShoppingItem(role) && navigator.onLine && !networkUnavailable && !hasPendingMutation && (
                  <button className="item-remove-button" type="button" aria-label={`Remove ${item.text}`} disabled={busyId === item.id} onClick={() => void runItemAction(item, 'remove')}>
                    Remove
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {role === 'VIEWER' && <p className="member-privacy-note">You can view this list, but only family and helpers can update it.</p>}
    </section>
  );
}
