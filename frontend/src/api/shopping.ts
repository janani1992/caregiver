import { currentUserId } from './session';
import type { MemberRole } from './members';

export type ListItemStatus = 'NEEDED' | 'CLAIMED' | 'BOUGHT' | 'NOT_FOUND';

export type ListItem = {
  id: string;
  circleId: string;
  text: string;
  qty: number | null;
  unit: string | null;
  shopGroup: string | null;
  status: ListItemStatus;
  claimedBy: string | null;
  claimedByName: string | null;
  createdBy: string;
  createdByName: string;
  createdAt: string;
  updatedAt: string;
  version: number;
};

export type CircleItems = { circleId: string; items: ListItem[] };
export type ShoppingItemInput = { id: string; text: string; qty?: number; shopGroup?: string };

async function getError(response: Response): Promise<Error> {
  const result: unknown = await response.json().catch(() => undefined);
  const message =
    typeof result === 'object' && result !== null && 'message' in result && typeof result.message === 'string'
      ? result.message
      : 'The shopping list could not be updated. Please try again.';
  return new Error(message);
}

function headers(idempotencyKey?: string): HeadersInit {
  return {
    'X-User-Id': currentUserId,
    ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
  };
}

export async function getShoppingItems(circleId: string): Promise<CircleItems> {
  const response = await fetch(`/v1/circles/${circleId}/items`, { headers: headers(), cache: 'no-store' });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<CircleItems>;
}

export async function addShoppingItem(circleId: string, input: ShoppingItemInput): Promise<ListItem> {
  const response = await fetch(`/v1/circles/${circleId}/items`, {
    method: 'POST',
    headers: { ...headers(crypto.randomUUID()), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<ListItem>;
}

export async function claimShoppingItem(itemId: string): Promise<ListItem> {
  const response = await fetch(`/v1/items/${itemId}/claim`, {
    method: 'POST',
    headers: headers(crypto.randomUUID()),
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<ListItem>;
}

export async function markShoppingItemBought(item: ListItem): Promise<ListItem> {
  const response = await fetch(`/v1/items/${item.id}/status`, {
    method: 'POST',
    headers: {
      ...headers(crypto.randomUUID()),
      'Content-Type': 'application/json',
      'If-Match': String(item.version),
    },
    body: JSON.stringify({ status: 'BOUGHT' }),
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<ListItem>;
}

export async function removeShoppingItem(itemId: string): Promise<void> {
  const response = await fetch(`/v1/items/${itemId}`, {
    method: 'DELETE',
    headers: headers(crypto.randomUUID()),
  });
  if (!response.ok) throw await getError(response);
}

export function canWriteShoppingList(role: MemberRole): boolean {
  return role === 'OWNER' || role === 'FAMILY' || role === 'HELPER';
}

export function canDeleteShoppingItem(role: MemberRole): boolean {
  return role === 'OWNER' || role === 'FAMILY';
}
