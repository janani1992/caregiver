import { currentUserId } from './session';

export type MemberRole = 'OWNER' | 'FAMILY' | 'HELPER' | 'VIEWER';

export type CircleMember = {
  userId: string;
  displayName: string;
  role: MemberRole;
  joinedAt: string;
};

export type CircleMembers = {
  circleId: string;
  circleName: string;
  currentUserRole: MemberRole;
  members: CircleMember[];
};

async function getError(response: Response): Promise<Error> {
  const result: unknown = await response.json().catch(() => undefined);
  const message =
    typeof result === 'object' && result !== null && 'message' in result && typeof result.message === 'string'
      ? result.message
      : 'Circle members could not be loaded. Please try again.';
  return new Error(message);
}

export async function getCircleMembers(circleId: string): Promise<CircleMembers> {
  const response = await fetch(`/v1/circles/${circleId}/members`, {
    headers: { 'X-User-Id': currentUserId },
    cache: 'no-store',
  });
  if (!response.ok) throw await getError(response);
  return response.json() as Promise<CircleMembers>;
}

export async function removeCircleMember(circleId: string, userId: string, idempotencyKey: string): Promise<void> {
  const response = await fetch(`/v1/circles/${circleId}/members/${userId}`, {
    method: 'DELETE',
    headers: {
      'Idempotency-Key': idempotencyKey,
      'X-User-Id': currentUserId,
    },
  });
  if (!response.ok) throw await getError(response);
}
