# Care Circle

A modular-monolith starter for the Care Circle MVP. The first implemented story is creating a circle and atomically assigning its creator the `OWNER` role.

## Story 1 contract

`POST /v1/circles` accepts a client-generated UUID, a name, and an IANA timezone. Send an `Idempotency-Key` header and, in local development only, an `X-User-Id` header. The API returns `201` with the created circle and `OWNER` role. Replaying a key with the same request returns the original circle; using that key for a different request returns `409`.

The development user header is a temporary authentication adapter, not security. Do not enable the `local` profile in production. Real phone/OTP authentication is a separate story.

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

Circle creation currently persists circle, owner membership, and idempotency record in one PostgreSQL transaction. Durable database idempotency was used here to make the create operation atomic; reconcile this with the architecture's Redis TTL approach in an ADR before standardizing idempotency across endpoints. Circle creation starts `change_seq` at one; invite creation and acceptance advance it transactionally. Define the durable generic change-feed record before implementing sync. Real authentication, RLS, and post-create circle functionality remain future work.

## Story 2: invite a member

Circle owners can create a shareable invite for a `FAMILY`, `HELPER`, or `VIEWER` role. Links expire after seven days. The browser generates a 256-bit bearer token; the API stores only its SHA-256 hash, and invite responses use `Cache-Control: no-store`. The recipient can preview the circle and role before accepting. Membership creation and invite consumption are atomic, and accepting again as the same user is safe. SMS/email delivery and real authentication remain future stories.
