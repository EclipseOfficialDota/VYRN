# Decisions

## 2026-09-30 — user-confirmed
- Use the explicitly requested VYRN child directory, preserving the parent workspace.
- Pin official Telegram Android at f2908b14133bbffbf7ab04f641ecb5bfaf533242; preserve source attribution and GPL redistribution requirements.
- Keep Telegram UI and migrate networking behind a compatibility layer, domain models and repositories. Do not describe a replacement UI as the Telegram UI.
- Go modular monolith, PostgreSQL, authenticated local media storage; no Redis until measured need.
- Email is a normalized login identifier only; invite registration, Argon2id, hashed rotating tokens, recovery codes and three-device limit.
- Private chats must fail closed until a reviewed E2EE protocol implementation and device verification are integrated. Never label server-readable messages E2EE.
- Calls remain disabled pending provider inbound UDP approval and end-to-end TURN verification.
- Text VYRN placeholder until the user supplies the final SVG.
- Debug keys only for early builds. Final signing is explicitly gated on user-provided environment variables.
- Prepare encrypted backup and restoration for S3-compatible storage; no claim of durable backup until an external destination and restore test exist.
- Generate a dedicated SSH key locally. Do not disable root or password login without explicit user permission.
- Cloudflare variables are read from environment only. Missing variables block DNS mutations, not local development.
- All access tokens, passwords, keys, configuration secrets, private backups and local tooling stay outside version control.

## 2026-10-01 — implementation checkpoints
- Keep reproducible Android overlays outside the generated upstream checkout; preserve original sources/licenses.
- Verify running Compose and actual APK binary properties, not configuration parsing alone.
- Serialize administrator refresh so concurrent requests cannot reuse a rotating refresh token.
- Reserve space across concurrent uploads, retain new orphans seven days, and return reaction/receipt read models for synchronization.
- Pin Go 1.26.8 and its Docker digest after 1.26.4 failed the official vulnerability scan. OpenPGP is not imported; do not adopt the unmaintained package.
- Use a separate local Docker project and headless Android emulator for verification. Production access and calls remain gated.
- Use opt-in dev/debug HTTPS with a localhost-scoped public CA for Android integration. Do not alter system trust stores or permit this configuration in release builds.
- Assign positive stable message ordinals on the server for the Telegram UI; UUIDs remain API identity. Use ordinal pagination so fetch order and device-local insertion order cannot reverse message IDs.

## 2026-10-02 — outgoing offline messages
- Persist group-text sends before network access. Bind entries and HTTP authentication to the originating user, retain stable client IDs, preserve per-conversation queue order, and persist acknowledgements before notifying the Telegram adapter.
- Share the token-vault lock across repository instances so rotating refresh tokens cannot be reused concurrently and queued sends cannot switch identities mid-request.
- Retry transient failures with a bounded exponential backoff and jitter. Keep failures available for explicit retry; remove accepted payload text from the outbox.
- Pending cancellation/reconciliation remains a mandatory acceptance gap, not an optional follow-up.

## 2026-10-02 — incoming foreground synchronization
- Authenticate WebSockets with the bearer header and keep credentials out of URLs. Persist raw events and the resume cursor atomically before acknowledging a batch.
- Process the durable inbox in sequence, cap handler retries at8 with exponential backoff, retain dead letters and prune successfully processed journal rows to the newest1000 per account.
- Treat the current incoming-message adapter as a foreground checkpoint. Complete every event mapping, crash recovery, background scheduling and notification behavior before production acceptance.

## 2026-10-02 — outbox lifecycle
- Claim queued rows atomically as SENDING before HTTP access. Recover interrupted claims only on sole-sender startup and retain the stable client ID for server deduplication.
- Report local cancellation only when no sender has ever claimed the row. Preserve uncertain sends for server reconciliation, including legacy rows whose attempt history cannot be proved.
- Keep accepted acknowledgement records for seven days and cancelled records for one day. Preserve pending and failed payloads for explicit user resolution.
