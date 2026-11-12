import { currentUserId } from './session';
import type { MemberRole } from './members';

export type UrgentRequestState = 'OPEN' | 'ON_IT' | 'RESOLVED';
export type UrgentRequest = {
  id: string;
  circleId: string;
  itemId: string | null;
  text: string;
  state: UrgentRequestState;
  sender: string;
  senderName: string;
  createdAt: string;
  handledBy: string | null;
  handledByName: string | null;
  handledAt: string | null;
  resolvedBy: string | null;
  resolvedByName: string | null;
  resolvedAt: string | null;
  version: number;
  seenByMe: boolean;
};

export type UrgentRequestList = { circleId: string; requests: UrgentRequest[] };

async function getError(response: Response): Promise<Error> {
  const result: unknown = await response.json().catch(() => undefined);
  const message =
    typeof result === 'object' && result !== null && 'message' in result && typeof result.message === 'string'
      ? result.message
      : 'The urgent request could not be updated. Please try again.';
  return new Error(message);
}

function headers(idempotencyKey?: string): HeadersInit {
  return {
    'X-User-Id': currentUserId,
    ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
  };
}

export async function getUrgentRequests(circleId: string): Promise<UrgentRequestList> {
  const response = await fetch(`/v1/circles/${circleId}/urgent`, {
    headers: headers(),
    cache: 'no-store',
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<UrgentRequestList>;
}

export async function createUrgentRequest(circleId: string, text: string): Promise<UrgentRequest> {
  const response = await fetch(`/v1/circles/${circleId}/urgent`, {
    method: 'POST',
    headers: { ...headers(crypto.randomUUID()), 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: crypto.randomUUID(), text }),
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<UrgentRequest>;
}

export async function acknowledgeUrgentRequest(
  requestId: string,
  acknowledgement: 'SEEN' | 'ON_IT',
): Promise<UrgentRequest> {
  const response = await fetch(`/v1/urgent/${requestId}/ack`, {
    method: 'POST',
    headers: { ...headers(crypto.randomUUID()), 'Content-Type': 'application/json' },
    body: JSON.stringify({ acknowledgement }),
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<UrgentRequest>;
}

export async function resolveUrgentRequest(requestId: string): Promise<UrgentRequest> {
  const response = await fetch(`/v1/urgent/${requestId}/resolve`, {
    method: 'POST',
    headers: headers(crypto.randomUUID()),
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<UrgentRequest>;
}

export function canCreateUrgentRequest(role: MemberRole): boolean {
  return role === 'OWNER' || role === 'FAMILY';
}

export function canTakeUrgentRequest(role: MemberRole): boolean {
  return role === 'OWNER' || role === 'FAMILY' || role === 'HELPER';
}

export function canResolveUrgentRequest(
  role: MemberRole,
  request: UrgentRequest,
  actorId: string,
): boolean {
  return role === 'OWNER' || role === 'FAMILY' || request.sender === actorId || request.handledBy === actorId;
}
