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
- Request-token cancellation in the Telegram UI remains a mandatory acceptance gap. Durable store/sender cancellation and server reconciliation are tested separately below.

## 2026-10-02 — incoming foreground synchronization
- Authenticate WebSockets with the bearer header and keep credentials out of URLs. Persist raw events and the resume cursor atomically before acknowledging a batch.
- Process the durable inbox in sequence, cap handler retries at8 with exponential backoff, retain dead letters and prune successfully processed journal rows to the newest1000 per account.
- Treat the current incoming-message adapter as a foreground checkpoint. Complete every event mapping, crash recovery, background scheduling and notification behavior before production acceptance.

## 2026-10-02 — outbox lifecycle
- Claim queued rows atomically as SENDING before HTTP access. Recover interrupted claims only on sole-sender startup and retain the stable client ID for server deduplication.
- Report local cancellation only when no sender has ever claimed the row. Preserve uncertain sends for server reconciliation, including legacy rows whose attempt history cannot be proved.
- Keep accepted acknowledgement records for seven days and cancelled records for one day. Preserve pending and failed payloads for explicit user resolution.
- Resolve uncertain cancellations transactionally on the server. Share a sender/client-ID advisory lock between send and cancel; permanently fence absent sends and return the original message ID for committed sends. Retain fences for delayed offline replays; deleting a committed message remains an explicit separate operation.

## 2026-10-02 — durable Android cancellation
- Persist cancellation intent before scheduling network reconciliation. Keep an active send claim exclusive; a failed send with newly recorded cancellation retries cancellation instead of its original payload.
- Prioritize cancellation intents, preserve them through bounded retries and process restart, and prevent explicit resend from reviving a cancelled client ID.
- Confirm cancellation only after local proof of never-attempted delivery or a server cancellation fence. Reconcile committed sends through an account-bound message fetch and persist the acknowledgement before completing callbacks.
- Keep Telegram request-token wiring and interactive UI acceptance open; internal sender tests do not establish that user flow.

## 2026-10-02 — Telegram request lifecycle
- Route request tokens, GUID binding and cancellation through the VYRN adapter rather than the disabled Telegram native transport. Key outstanding requests by account and token and remove them on terminal delivery.
- Suppress cancelled read callbacks. For sends, wait for durable cancellation proof before invoking onCancelled; preserve the original send acknowledgement when the server committed first.
- Keep the originating user immutable in request metadata. Persist cancellation intent without waiting for the token-vault network lock; reconciliation still sends only under the matching active account.
- Post completion handling onto Telegram's stage queue so future callbacks do not acquire other request locks inline during cancellation.
- Interactive SendMessagesHelper cancellation remains open: its original optimistic local removal must be reconciled with confirmed cancellation and UI cleanup must survive process death.

## 2026-10-02 — UI cancellation journal
- Persist originating user/client and original negative message/dialog/random IDs atomically with cancellation intent. Treat missing local send history conservatively and obtain server proof rather than claiming it was never sent.
- Retain terminal outbox entries until the exact UI identity is acknowledged after durable cache cleanup. Preserve committed sends and reject attempts to replace a journal entry with different UI IDs.
- Schema4 introduces this journal and preserves populated older queues. Its adapter and Telegram cache integration remain to be implemented and tested.

## 2026-10-02 — confirmed Telegram cache reconciliation
- Intercept pending group-text cancellation in SendMessagesHelper, retain negative local rows until cancellation proof, and replay terminal journal records after process death. Restore committed sends using their original server IDs.
- Verify native cache changes before acknowledging the durable journal. Preserve originating-user, active native-account and random-ID checks without acquiring the network token lock on UI/storage queues.
- Ignore Telegram native onLogout: absence of an upstream Telegram auth key cannot revoke an independent VYRN session. VYRN authentication owns session validity.

## 2026-10-02 — deleted committed sends
- Preserve the original SENT acknowledgement when a committed send has subsequently been deleted. An authorized server tombstone removes both the original pending row and any stale positive native-cache row; it must never become an empty replacement message.
- Verify absence of both native rows before acknowledging the UI journal. Retain the journal on any identity, cache or network failure.

## 2026-10-02 — user discovery and native group creation
- Search only public display names after authentication; expose no login email/admin data. Bound queries to100 characters and results to50, treat SQL wildcards literally and omit self, disabled users and blocks in either direction.
- Route Telegram contacts.search and messages.createChat through repositories. Return the invitedUsers/updates/chat/user shapes expected by the original MessagesController rather than replacing its group-creation UI.
- Serialize mapped user name/photo/status flags correctly so native cache persistence preserves display names. Nonzero create-time auto-delete remains unavailable until it is applied atomically on the server.
