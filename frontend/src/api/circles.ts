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
  name: string;
  timezone: string;
};

const DEVELOPMENT_USER_ID =
  import.meta.env.VITE_DEV_USER_ID ?? '087a4d5a-9a7b-4ab1-b64f-7c7b00b3e104';

export async function createCircle(input: CreateCircleInput, idempotencyKey: string): Promise<Circle> {
  const response = await fetch('/v1/circles', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-User-Id': DEVELOPMENT_USER_ID,
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
