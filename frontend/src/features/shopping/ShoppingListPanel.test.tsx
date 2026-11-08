import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ShoppingListPanel from './ShoppingListPanel';
import * as membersApi from '../../api/members';
import * as shoppingApi from '../../api/shopping';

vi.mock('../../api/members', () => ({ getCircleMembers: vi.fn() }));
vi.mock('../../api/shopping', () => ({
  addShoppingItem: vi.fn(),
  canDeleteShoppingItem: (role: string) => role === 'OWNER' || role === 'FAMILY',
  canWriteShoppingList: (role: string) => role !== 'VIEWER',
  claimShoppingItem: vi.fn(),
  getShoppingItems: vi.fn(),
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
    vi.mocked(shoppingApi.getShoppingItems).mockResolvedValue({ circleId: 'circle-1', items: [item] });
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
});
