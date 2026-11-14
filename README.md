# Care Circle

A modular-monolith starter for the Care Circle MVP. The first implemented story is creating a circle and atomically assigning its creator the `OWNER` role.

## Story 1 contract

`POST /v1/circles` accepts a client-generated UUID, the creator's display name, a circle name, and an IANA timezone. Send an `Idempotency-Key` header and, in local development only, an `X-User-Id` header. The API returns `201` with the created circle and `OWNER` role. Replaying a key with the same request returns the original circle; using that key for a different request returns `409`.

The development user header is a temporary authentication adapter, not security. Do not enable the `local` profile in production. Real phone/OTP authentication is a separate story.

Care Log entry text is encrypted at rest. The local profile supplies a development-only key; deployments must set a dedicated, stable base64-encoded 32-byte `CARE_LOG_ENCRYPTION_KEY` secret. Do not reuse the development key or change a deployed key without a data re-encryption plan.

## Run locally

Requirements: JDK 21, Maven 3.9+, Node.js 20.19+ or 22.12+, npm, and Docker Compose.

1. Start PostgreSQL: `docker compose up -d postgres`
2. Start the API: `cd backend && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`
3. In another terminal, start the PWA: `cd frontend && npm install && npm run dev`
4. Open the Vite URL (normally `http://localhost:5173`). The dev API proxy forwards `/v1` to port 8080.

The development account is configured in `frontend/.env.example`; copy it to `frontend/.env.local` only if you want to use another UUID. To try the invite flow, create a link as the default owner, then set `VITE_DEV_USER_ID` in `frontend/.env.local` to a different UUID and reload the invite link as that test account. Flyway initializes the database on API startup.

## Tests

- Backend: `cd backend && mvn test` (uses an isolated H2 database).
- UI: `cd frontend && npm install && npm test`.
- UI production build: `cd frontend && npm run build`.

## Story boundary and follow-ups

Circle creation currently persists circle, owner membership, profile, and idempotency record in one PostgreSQL transaction. Durable database idempotency was used here to make the create operation atomic; reconcile this with the architecture's Redis TTL approach in an ADR before standardizing idempotency across endpoints. Circle creation starts `change_seq` at one; invite creation, acceptance, and member removal advance it transactionally. Define the durable generic change-feed record before implementing sync. Real authentication and RLS remain future work.

## Story 7: care log

Active circle members can read the append-only Care Log; owners, family members, and helpers can add a categorized update. Entries are ordered newest-first and paginated with a stable cursor. Entry text is encrypted in the database with AES-256-GCM, and successful writes increment the circle's `change_seq` transactionally. Editing/deleting entries and broader offline synchronization remain future stories.

## Story 8: initial offline support

The service worker caches the app shell and build assets, but never caches `/v1` responses. The shopping list stores a device-local snapshot and queues new-item additions with stable item IDs and idempotency keys; queued adds sync through the normal API after reconnecting. Claims, status changes, removals, Care Log, urgent requests, and members still require a network connection. IndexedDB data is not application-encrypted, so avoid this feature on shared devices. A generic change feed and broader offline conflict handling remain future work.

## Story 2: invite a member

Circle owners can create a shareable invite for a `FAMILY`, `HELPER`, or `VIEWER` role. Links expire after seven days. The browser generates a 256-bit bearer token; the API stores only its SHA-256 hash, and invite responses use `Cache-Control: no-store`. The recipient can preview the circle and role before accepting. Membership creation and invite consumption are atomic, and accepting again as the same user is safe. SMS/email delivery and real authentication remain future stories.

## Story 3: view and manage members

Active members can view a roster containing only display name, role, and join date. Only owners can remove non-owners, with confirmation in the UI; self-removal and removing another owner are rejected. Removal is soft, blocks further circle access, and leaves an audit record. A removed member can rejoin only by accepting a new invite; their membership is reactivated and prior removal history is preserved.
