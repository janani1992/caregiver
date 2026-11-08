import { useCallback, useEffect, useMemo, useState } from 'react';
import { getCircleMembers, type MemberRole } from '../../api/members';
import {
  addShoppingItem,
  canDeleteShoppingItem,
  canWriteShoppingList,
  claimShoppingItem,
  getShoppingItems,
  markShoppingItemBought,
  removeShoppingItem,
  type ListItem,
  type ListItemStatus,
} from '../../api/shopping';

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

  const reload = useCallback(async () => {
    setError('');
    try {
      const [list, roster] = await Promise.all([getShoppingItems(circleId), getCircleMembers(circleId)]);
      setItems(list.items);
      setRole(roster.currentUserRole);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The shopping list could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [circleId]);

  useEffect(() => { void reload(); }, [reload]);

  const visibleItems = useMemo(
    () => filter === 'ALL' ? items : items.filter((item) => item.status === filter),
    [filter, items],
  );

  async function submitItem(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanText = text.trim();
    if (!cleanText || saving) return;
    setSaving(true);
    setError('');
    try {
      const quantity = qty.trim() ? Number(qty) : undefined;
      await addShoppingItem(circleId, {
        id: crypto.randomUUID(),
        text: cleanText,
        ...(quantity ? { qty: quantity } : {}),
        ...(shopGroup.trim() ? { shopGroup: shopGroup.trim() } : {}),
      });
      setText('');
      setQty('');
      setShopGroup('');
      await reload();
    } catch (reason) {
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
      setError(reason instanceof Error ? reason.message : 'The item could not be updated.');
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
      ) : visibleItems.length === 0 ? (
        <div className="shopping-empty">
          <span aria-hidden="true">✳</span>
          <p>{items.length === 0 ? 'Your list is ready when you are.' : 'No items in this section yet.'}</p>
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
              </div>
              <span className={`item-status-label status-label-${item.status.toLowerCase()}`}>{statusLabels[item.status]}</span>
              {role && canWriteShoppingList(role) && item.status === 'NEEDED' && (
                <button className="item-action-button" type="button" disabled={busyId === item.id} onClick={() => void runItemAction(item, 'claim')}>
                  {busyId === item.id ? '…' : 'I’ll get it'}
                </button>
              )}
              {role && canWriteShoppingList(role) && item.status === 'CLAIMED' && (
                <button className="item-action-button" type="button" disabled={busyId === item.id} onClick={() => void runItemAction(item, 'bought')}>
                  {busyId === item.id ? '…' : 'Mark bought'}
                </button>
              )}
              {role && canDeleteShoppingItem(role) && (
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
