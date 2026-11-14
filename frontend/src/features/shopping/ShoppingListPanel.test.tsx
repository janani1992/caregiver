import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ShoppingListPanel from './ShoppingListPanel';
import * as membersApi from '../../api/members';
import * as shoppingApi from '../../api/shopping';
import * as offlineStore from '../../offline/shoppingStore';

vi.mock('../../api/members', () => ({ getCircleMembers: vi.fn() }));
vi.mock('../../api/session', () => ({ currentUserId: 'owner-1' }));
vi.mock('../../offline/shoppingStore', () => ({
  getQueuedShoppingAdds: vi.fn(),
  getShoppingSnapshot: vi.fn(),
  isRetryableShoppingError: (error: unknown) => !(error instanceof Error) || error.name === 'TypeError',
  isShoppingAccessRevoked: (error: unknown) => error instanceof Error && [401, 403, 404].includes((error as Error & { status?: number }).status ?? 0),
  markQueuedShoppingAddFailed: vi.fn(),
  queueShoppingAdd: vi.fn(),
  removeQueuedShoppingAdd: vi.fn(),
  removeShoppingSnapshot: vi.fn(),
  retryQueuedShoppingAdd: vi.fn(),
  saveShoppingSnapshot: vi.fn(),
}));
vi.mock('../../api/shopping', () => ({
  addShoppingItem: vi.fn(),
  canDeleteShoppingItem: (role: string) => role === 'OWNER' || role === 'FAMILY',
  canWriteShoppingList: (role: string) => role !== 'VIEWER',
  claimShoppingItem: vi.fn(),
  getShoppingItems: vi.fn(),
  isRetryableShoppingError: (error: unknown) => !(error instanceof Error) || error.name === 'TypeError',
  isShoppingAccessRevoked: (error: unknown) => error instanceof Error && [401, 403, 404].includes((error as Error & { status?: number }).status ?? 0),
  markShoppingItemBought: vi.fn(),
  removeShoppingItem: vi.fn(),
}));

const item = {
  id: 'item-1',
  circleId: 'circle-1',
  text: 'Fresh milk',
  qty: 2,
  unit: 'packs',
  shopGroup: 'Grocery',
  status: 'NEEDED' as const,
  claimedBy: null,
  claimedByName: null,
  createdBy: 'owner-1',
  createdByName: 'Alex',
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z',
  version: 1,
};

const queuedAdd: offlineStore.QueuedShoppingAdd = {
  queueId: 'owner-1:circle-1:queued-item-1',
  userId: 'owner-1',
  circleId: 'circle-1',
  input: { id: 'queued-item-1', text: 'Queued milk' },
  idempotencyKey: 'queued-idempotency-key',
  queuedAt: '2026-11-14T10:00:00Z',
  failureMessage: null,
};

const originalOnline = navigator.onLine;

function mockRole(role: 'OWNER' | 'FAMILY' | 'HELPER' | 'VIEWER') {
  vi.mocked(membersApi.getCircleMembers).mockResolvedValue({
    circleId: 'circle-1',
    circleName: 'The Tan family',
    currentUserRole: role,
    members: [],
  });
}

describe('ShoppingListPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    vi.mocked(shoppingApi.getShoppingItems).mockResolvedValue({ circleId: 'circle-1', items: [item] });
    vi.mocked(offlineStore.getShoppingSnapshot).mockResolvedValue(undefined);
    vi.mocked(offlineStore.getQueuedShoppingAdds).mockResolvedValue([]);
    vi.mocked(offlineStore.saveShoppingSnapshot).mockResolvedValue();
    vi.mocked(offlineStore.removeQueuedShoppingAdd).mockResolvedValue();
    vi.mocked(offlineStore.removeShoppingSnapshot).mockResolvedValue();
    vi.mocked(offlineStore.retryQueuedShoppingAdd).mockResolvedValue();
    vi.mocked(offlineStore.markQueuedShoppingAddFailed).mockResolvedValue();
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: originalOnline });
  });

  it('lets helpers add and advance an item but does not show delete controls', async () => {
    mockRole('HELPER');
    vi.mocked(shoppingApi.claimShoppingItem).mockResolvedValue({ ...item, status: 'CLAIMED', claimedBy: 'helper-1', version: 2 });
    vi.mocked(shoppingApi.markShoppingItemBought).mockResolvedValue({ ...item, status: 'BOUGHT', version: 3 });
    render(<ShoppingListPanel circleId="circle-1" />);

    expect(await screen.findByText('Fresh milk')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add item' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'I’ll get it' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove Fresh milk' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'I’ll get it' }));
    await waitFor(() => expect(shoppingApi.claimShoppingItem).toHaveBeenCalledWith(item.id));
  });

  it('keeps the list readable for viewers without mutation controls', async () => {
    mockRole('VIEWER');
    render(<ShoppingListPanel circleId="circle-1" />);

    expect(await screen.findByText('Fresh milk')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add item' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'I’ll get it' })).not.toBeInTheDocument();
    expect(screen.getByText(/only family and helpers can update/i)).toBeInTheDocument();
  });

  it('shows removal controls to family members', async () => {
    mockRole('FAMILY');
    render(<ShoppingListPanel circleId="circle-1" />);

    expect(await screen.findByRole('button', { name: 'Remove Fresh milk' })).toBeInTheDocument();
  });

  it('lets helpers mark a claimed item bought', async () => {
    mockRole('HELPER');
    const claimedItem = { ...item, status: 'CLAIMED' as const, claimedBy: 'helper-1', claimedByName: 'Siti', version: 2 };
    vi.mocked(shoppingApi.getShoppingItems).mockResolvedValue({ circleId: 'circle-1', items: [claimedItem] });
    vi.mocked(shoppingApi.markShoppingItemBought).mockResolvedValue({ ...claimedItem, status: 'BOUGHT', version: 3 });
    render(<ShoppingListPanel circleId="circle-1" />);

    fireEvent.click(await screen.findByRole('button', { name: 'Mark bought' }));
    await waitFor(() => expect(shoppingApi.markShoppingItemBought).toHaveBeenCalledWith(claimedItem));
  });

  it('shows the saved list and queues an add while offline without claiming it was synced', async () => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
    vi.mocked(offlineStore.getShoppingSnapshot).mockResolvedValue({
      userId: 'owner-1',
      circleId: 'circle-1',
      role: 'OWNER',
      items: [item],
      savedAt: '2026-11-14T09:00:00Z',
    });
    vi.mocked(offlineStore.getQueuedShoppingAdds)
      .mockResolvedValueOnce([])
      .mockResolvedValue([queuedAdd]);
    vi.mocked(offlineStore.queueShoppingAdd).mockResolvedValue(queuedAdd);
    render(<ShoppingListPanel circleId="circle-1" />);

    expect(await screen.findByText('Fresh milk')).toBeInTheDocument();
    expect(screen.getByText(/You’re offline/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'I’ll get it' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Add an item'), { target: { value: 'Queued milk' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));

    await waitFor(() => expect(offlineStore.queueShoppingAdd).toHaveBeenCalledWith(
      'owner-1',
      'circle-1',
      expect.objectContaining({ id: expect.any(String), text: 'Queued milk' }),
      expect.any(String),
    ));
    expect(await screen.findByText('Waiting to sync')).toBeInTheDocument();
    expect(shoppingApi.addShoppingItem).not.toHaveBeenCalled();
  });

  it('replays queued adds with the original idempotency key and removes them after success', async () => {
    mockRole('OWNER');
    vi.mocked(offlineStore.getShoppingSnapshot).mockResolvedValue({
      userId: 'owner-1',
      circleId: 'circle-1',
      role: 'OWNER',
      items: [],
      savedAt: '2026-11-14T09:00:00Z',
    });
    vi.mocked(offlineStore.getQueuedShoppingAdds)
      .mockResolvedValueOnce([queuedAdd])
      .mockResolvedValueOnce([queuedAdd])
      .mockResolvedValueOnce([])
      .mockResolvedValue([]);
    vi.mocked(shoppingApi.addShoppingItem).mockResolvedValue(item);
    render(<ShoppingListPanel circleId="circle-1" />);

    await waitFor(() => expect(shoppingApi.addShoppingItem).toHaveBeenCalledWith(
      'circle-1',
      queuedAdd.input,
      queuedAdd.idempotencyKey,
    ));
    expect(offlineStore.removeQueuedShoppingAdd).toHaveBeenCalledWith(queuedAdd.queueId);
  });

  it('discards the cached snapshot after the server rejects circle access', async () => {
    mockRole('OWNER');
    vi.mocked(offlineStore.getShoppingSnapshot).mockResolvedValue({
      userId: 'owner-1',
      circleId: 'circle-1',
      role: 'OWNER',
      items: [item],
      savedAt: '2026-11-14T09:00:00Z',
    });
    vi.mocked(shoppingApi.getShoppingItems).mockRejectedValue(Object.assign(new Error('Circle access was removed'), { status: 403 }));
    render(<ShoppingListPanel circleId="circle-1" />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Circle access was removed');
    expect(screen.queryByText('Fresh milk')).not.toBeInTheDocument();
    expect(offlineStore.removeShoppingSnapshot).toHaveBeenCalledWith('owner-1', 'circle-1');
  });
});
