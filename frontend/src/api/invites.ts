import { currentUserId } from './session';

export type InviteRole = 'FAMILY' | 'HELPER' | 'VIEWER';

export type CircleInvite = {
  id: string;
  circleId: string;
  role: InviteRole;
  inviteUrl: string;
  expiresAt: string;
};

export type InvitePreview = {
  circleId: string;
  circleName: string;
  role: InviteRole;
  expiresAt: string;
};

export type InviteAcceptance = {
  circleId: string;
  circleName: string;
  role: InviteRole;
  acceptedAt: string;
  alreadyAccepted: boolean;
};

async function responseError(response: Response): Promise<Error> {
  const result: unknown = await response.json().catch(() => undefined);
  const message =
    typeof result === 'object' && result !== null && 'message' in result && typeof result.message === 'string'
      ? result.message
      : 'The invite could not be processed. Please try again.';
  return new Error(message);
}

export async function createInvite(
  circleId: string,
  role: InviteRole,
  id: string,
  token: string,
  idempotencyKey: string,
): Promise<CircleInvite> {
  const response = await fetch(`/v1/circles/${circleId}/invites`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-User-Id': currentUserId,
    },
    body: JSON.stringify({ id, role, token }),
  });
  if (!response.ok) throw await responseError(response);
  return response.json() as Promise<CircleInvite>;
}

export async function getInvitePreview(token: string): Promise<InvitePreview> {
  const response = await fetch(`/v1/invites/${encodeURIComponent(token)}`);
  if (!response.ok) throw await responseError(response);
  return response.json() as Promise<InvitePreview>;
}

export async function acceptInvite(
  token: string,
  displayName: string,
  idempotencyKey: string,
): Promise<InviteAcceptance> {
  const response = await fetch(`/v1/invites/${encodeURIComponent(token)}/accept`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-User-Id': currentUserId,
    },
    body: JSON.stringify({ displayName }),
  });
  if (!response.ok) throw await responseError(response);
  return response.json() as Promise<InviteAcceptance>;
}
