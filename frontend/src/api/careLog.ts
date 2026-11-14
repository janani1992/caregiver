import { currentUserId } from './session';
import type { MemberRole } from './members';

export type CareLogCategory = 'GENERAL' | 'MEDICATION' | 'APPOINTMENT' | 'WELLBEING' | 'OTHER';

export type CareLogEntry = {
  id: string;
  circleId: string;
  text: string;
  category: CareLogCategory;
  authorId: string;
  authorName: string;
  createdAt: string;
};

export type CareLogPage = {
  circleId: string;
  entries: CareLogEntry[];
  nextCursor: string | null;
};

export type CareLogEntryInput = {
  id: string;
  text: string;
  category: CareLogCategory;
};

async function getError(response: Response): Promise<Error> {
  const result: unknown = await response.json().catch(() => undefined);
  const message =
    typeof result === 'object' && result !== null && 'message' in result && typeof result.message === 'string'
      ? result.message
      : 'The care log could not be updated. Please try again.';
  return new Error(message);
}

function headers(idempotencyKey?: string): HeadersInit {
  return {
    'X-User-Id': currentUserId,
    ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
  };
}

export async function getCareLogEntries(circleId: string, cursor?: string, limit = 20): Promise<CareLogPage> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set('cursor', cursor);
  const response = await fetch(`/v1/circles/${circleId}/log?${params.toString()}`, {
    headers: headers(),
    cache: 'no-store',
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<CareLogPage>;
}

export async function addCareLogEntry(circleId: string, input: CareLogEntryInput): Promise<CareLogEntry> {
  const response = await fetch(`/v1/circles/${circleId}/log`, {
    method: 'POST',
    headers: { ...headers(crypto.randomUUID()), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<CareLogEntry>;
}

export function canWriteCareLog(role: MemberRole): boolean {
  return role === 'OWNER' || role === 'FAMILY' || role === 'HELPER';
}