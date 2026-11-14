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
  getShoppingSnapshot,
  markQueuedShoppingAddFailed,
  queueShoppingAdd,
  removeQueuedShoppingAdd,
  removeShoppingSnapshot,
  retryQueuedShoppingAdd,
  saveShoppingSnapshot,
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
  const [hasSavedSnapshot, setHasSavedSnapshot] = useState(false);
  const [snapshotSavedAt, setSnapshotSavedAt] = useState<string | null>(null);
  const [networkUnavailable, setNetworkUnavailable] = useState(false);
  const [dataUnavailableOffline, setDataUnavailableOffline] = useState(false);
  const hasSavedSnapshotRef = useRef(false);

  const refreshQueue = useCallback(async () => {
    setQueuedAdds(await getQueuedShoppingAdds(currentUserId, circleId));
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
        await Promise.all(queued.map((entry) => markQueuedShoppingAddFailed(entry.queueId, 'Circle access changed. Discard or contact a circle owner.')));
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

  const syncQueuedAdds = useCallback(async () => {
    if (!navigator.onLine) return;
    const queued = await getQueuedShoppingAdds(currentUserId, circleId).catch(() => []);
    let syncedAny = false;
    for (const queuedAdd of queued) {
      if (queuedAdd.failureMessage) continue;
      try {
        await addShoppingItem(circleId, queuedAdd.input, queuedAdd.idempotencyKey);
        await removeQueuedShoppingAdd(queuedAdd.queueId);
        syncedAny = true;
      } catch (reason) {
        if (isRetryableShoppingError(reason)) {
          setNetworkUnavailable(true);
          break;
        }
        const message = reason instanceof Error ? reason.message : 'This item could not be synced.';
        await markQueuedShoppingAddFailed(queuedAdd.queueId, message);
      }
    }
    await refreshQueue().catch(() => undefined);
    if (syncedAny) await reload();
  }, [circleId, refreshQueue, reload]);

  useEffect(() => {
    const handleOffline = () => {
      setNetworkUnavailable(true);
      setDataUnavailableOffline(!hasSavedSnapshotRef.current);
    };
    const handleOnline = () => { void (async () => { await syncQueuedAdds(); await reload(); })(); };
    const handleFocus = () => { void syncQueuedAdds(); };
    window.addEventListener('offline', handleOffline);
    window.addEventListener('online', handleOnline);
    window.addEventListener('focus', handleFocus);
    void (async () => { await reload(); await syncQueuedAdds(); })();
    return () => {
      window.removeEventListener('offline', handleOffline);
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('focus', handleFocus);
    };
  }, [reload, syncQueuedAdds]);

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
  const displayedItems = useMemo(() => {
    const knownIds = new Set(items.map((item) => item.id));
    return [...queuedAdds.map(toPendingItem).filter((item) => !knownIds.has(item.id)), ...items];
  }, [circleId, items, queuedAdds]);

  const visibleItems = useMemo(
    () => filter === 'ALL' ? displayedItems : displayedItems.filter((item) => item.status === filter),
    [displayedItems, filter],
  );

  async function discardQueuedAdd(queuedAdd: QueuedShoppingAdd) {
    await removeQueuedShoppingAdd(queuedAdd.queueId);
    await refreshQueue();
  }

  async function retryQueuedAdd(queuedAdd: QueuedShoppingAdd) {
    await retryQueuedShoppingAdd(queuedAdd.queueId);
    await refreshQueue();
    await syncQueuedAdds();
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
    try {
      if (action === 'claim') await claimShoppingItem(item.id);
      if (action === 'bought') await markShoppingItemBought(item);
      if (action === 'remove') await removeShoppingItem(item.id);
      await reload();
    } catch (reason) {
      if (isRetryableShoppingError(reason)) {
        setNetworkUnavailable(true);
        setError(reason instanceof Error ? reason.message : 'The item could not be updated.');
      } else {
        await reload();
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
          {visibleItems.map((item) => (
            <li className="shopping-item" key={item.id}>
              <span className={`item-status-dot status-${item.status.toLowerCase()}`} aria-hidden="true" />
              <div className="shopping-item-details">
                <strong>{item.text}{item.qty != null && <span className="item-quantity"> · {item.qty}{item.unit ? ` ${item.unit}` : ''}</span>}</strong>
                <span>
                  {item.shopGroup ? `${item.shopGroup} · ` : ''}
                  {item.status === 'CLAIMED' && item.claimedByName ? `Picked up by ${item.claimedByName}` : `Added by ${item.createdByName}`}
                </span>
                {queuedAdds.find(({ input }) => input.id === item.id)?.failureMessage && (
                  <span className="queued-item-error" role="status">
                    {queuedAdds.find(({ input }) => input.id === item.id)?.failureMessage}
                  </span>
                )}
              </div>
              {pendingIds.has(item.id) ? (
                <span className="item-status-label item-status-pending">
                    {queuedAdds.find(({ input }) => input.id === item.id)?.failureMessage ? 'Needs attention' : 'Waiting to sync'}
                </span>
              ) : <span className={`item-status-label status-label-${item.status.toLowerCase()}`}>{statusLabels[item.status]}</span>}
              {pendingIds.has(item.id) && (
                <>
                  {queuedAdds.find(({ input }) => input.id === item.id)?.failureMessage && (
                    <button className="item-action-button" type="button" onClick={() => {
                      const pending = queuedAdds.find(({ input }) => input.id === item.id);
                      if (pending) void retryQueuedAdd(pending);
                    }}>
                      Retry
                    </button>
                  )}
                  <button className="item-remove-button" type="button" aria-label={`Discard pending ${item.text}`} onClick={() => {
                    const pending = queuedAdds.find(({ input }) => input.id === item.id);
                    if (pending) void discardQueuedAdd(pending);
                  }}>
                    Discard
                  </button>
                </>
              )}
              {role && canWriteShoppingList(role) && !networkUnavailable && !pendingIds.has(item.id) && item.status === 'NEEDED' && (
                <button className="item-action-button" type="button" disabled={busyId === item.id} onClick={() => void runItemAction(item, 'claim')}>
                  {busyId === item.id ? '…' : 'I’ll get it'}
                </button>
              )}
              {role && canWriteShoppingList(role) && !networkUnavailable && item.status === 'CLAIMED' && (
                <button className="item-action-button" type="button" disabled={busyId === item.id} onClick={() => void runItemAction(item, 'bought')}>
                  {busyId === item.id ? '…' : 'Mark bought'}
                </button>
              )}
              {role && canDeleteShoppingItem(role) && !networkUnavailable && !pendingIds.has(item.id) && (
                <button className="item-remove-button" type="button" aria-label={`Remove ${item.text}`} disabled={busyId === item.id} onClick={() => void runItemAction(item, 'remove')}>
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {role === 'VIEWER' && <p className="member-privacy-note">You can view this list, but only family and helpers can update it.</p>}
    </section>
  );
}
