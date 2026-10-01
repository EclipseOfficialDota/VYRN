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
- Pending cancellation/reconciliation and incoming durable synchronization remain mandatory acceptance gaps, not optional follow-ups.
