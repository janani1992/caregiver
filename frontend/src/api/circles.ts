import { currentUserId } from './session';

export type Circle = {
  id: string;
  name: string;
  timezone: string;
  ownerUserId: string;
  role: 'OWNER';
  changeSeq: number;
};

export type CreateCircleInput = {
  id: string;
  displayName: string;
  name: string;
  timezone: string;
};

export async function createCircle(input: CreateCircleInput, idempotencyKey: string): Promise<Circle> {
  const response = await fetch('/v1/circles', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-User-Id': currentUserId,
    },
    body: JSON.stringify(input),
  });

  const result: unknown = await response.json().catch(() => undefined);
  if (!response.ok) {
    const message =
      typeof result === 'object' && result !== null && 'message' in result && typeof result.message === 'string'
        ? result.message
        : 'We could not create your circle. Please try again.';
    throw new Error(message);
  }
  return result as Circle;
}
