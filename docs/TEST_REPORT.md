# Verification report

Date: 2026-10-01. This records performed checks, not planned tests.

## Passed

- Go package build, including Windows executable `artifacts/vyrn-core.exe`.
- Password hashing: Argon2id round trip, incorrect password, salt uniqueness, malformed cost string and length rules.
- Email normalization and malformed address rejection; opaque-token uniqueness and encoding checks.
- PostgreSQL migrations applied twice to isolated test schemas without reapplying unchanged versions.
- Invite registration, case-insensitive duplicate account rejection and profile authorization.
- Three-device limit; refresh rotation and refresh reuse revocation; one-time recovery use.
- Group membership permissions, idempotent send, mismatched-retry conflict, stale-edit conflict, delete tombstone, reaction, receipt and pin permissions.
- Private plaintext rejection.
- Authorized attachment upload/download; outsiders and unbound-upload recipients rejected.
- Emergency disk reserve blocks upload while group text still sends.
- WebSocket authentication, event delivery, acknowledgement, disconnect and resume after an offline message.
- Eight concurrent identical sends resulted in one inserted message; login lockout and IP throttling.
- Administrator TOTP, time-step replay rejection and grant-protected dashboard.
- Update envelope signature, tamper rejection, expiry, rollback and download-host validation.
- Actual Docker image build for Core (`vyrn-core:dev`).
- `docker compose ... config -q` without exposing expanded secrets.
- Caddy configuration validation using its actual container image returned `Valid configuration` after fixing multiline block syntax.
- Local readiness endpoint returned `ready` against running PostgreSQL.
- Custom-format pg_dump restored with pg_restore into a separate database, verified two migrations and one bootstrap user, then removed that disposable database.
- Admin JavaScript syntax; browser rendering of English and Persian/RTL login form at a narrow viewport.
- Actual administrator login, TOTP verification, dashboard data and Persian group tab in the browser. No private values were logged.
- Seven PostgreSQL integration tests, including expiry tombstones, one-time expiry events, reattachment resets, seven-day orphan deletion and reaction/receipt read models.
- Three dev Debug APKs built: ARM64, ARMv7, Universal (both ARM ABIs). `apksigner` verified signatures; `aapt2` verified package/minAPI26/target36/ABIs and manifest policy. Evidence: artifacts/apk-verification.json.
- Independent local Docker Core/PostgreSQL stack reached healthy status. Fixed malformed unquoted tmpfs options found by the actual startup check.
- Core/PostgreSQL restart preserved existing sessions, messages and durable sync events; retry did not insert a duplicate. Passed again after the security toolchain update.
- Go 1.26.8 package tests and initial `go vet` check. Official govulncheck v1.8.0 initially found five standard-library issues in Go1.26.4; after pinning Go1.26.8 and rebuilding, zero affected code paths and zero imported-package vulnerabilities remained. The unused x/crypto/openpgp module advisory GO-2026-5932 has no call path in VYRN.
- Android35 x86_64 emulator installation, DefaultIcon launcher resolution, two cold launches/forced process restarts and process stability passed. No VYRN crash was found during the observation windows. Evidence: artifacts/android-smoke.json. The emulator image logged unrelated Bluetooth-service crashes before VYRN launch; this does not substitute for testing calling or Bluetooth behavior.

## Additional verification on 2026-10-01

- Android35 integration runner executes in the real foreground Activity against the isolated HTTPS Docker stack.17 named checks pass across two different app processes: TLS endpoint rejection, cleartext disabled, invite registration, Keystore storage, group delivery, idempotency, Persian text, serialized refresh, edit/reaction/receipt models, history pagination, stable message identifiers, Telegram dialog adapter, persisted session/history/ID map, deletion and revocation. Evidence: artifacts/android-integration.json. UI buttons/layout and background behavior are not covered.
- Background-only instrumentation initially failed DNS with EAI_NODATA. Bringing the real Activity to the foreground resolved the emulator network restriction. This is not a fix or acceptance claim for background synchronization.
- Nine PostgreSQL integration tests now pass, including ordering independent of timestamp changes, authorized single-message reads, removed-member rejection, delete/expiry content removal and populated version002-to003 migration with sequence continuation. Repeat migration, unit tests and go vet pass.
- Updated local Core Docker image and native executable start successfully with migration003. Core/PostgreSQL restart again preserves sessions/messages/events and retry idempotency.
- ARM64, ARMv7 and Universal debug APKs rebuilt after the message-order change. Signatures, ABI contents and manifest policy pass; binary resource inspection confirms system-root TLS and no local test CA in these normal APKs. Physical ARM installation is still untested.
- Linux Docker Go race-detector run passes all packages and PostgreSQL integration tests. The GitHub Actions workflow passes actionlint1.7.12; execution on GitHub is pending authenticated repository creation. Repository path/secret-pattern checks and a private comparison against actual local credential values pass. These checks supplement manual review and do not prove the absence of arbitrary undisclosed secrets.

## Android outbox checkpoint on 2026-10-02

- 29 named Android checks pass in three instrumentation processes. The test removes the local HTTPS reverse-forwarding rule for the offline process, then restores it after process death. Two group-text messages remain pending without false acknowledgement and later arrive in order, exactly once, through the Telegram request adapter.
- A server-committed message whose local acknowledgement was deliberately left pending is retried after restart without inserting a duplicate. The completed result is persisted before notifying the adapter, and its pending body is cleared.
- Retry budget stops at8 failures, explicit resend resets the budget, permanent rejection does not retry, and different payloads cannot reuse a queued client ID. Cross-account request rejection and concurrent refresh using two repository objects pass.
- Pending cancellation, background execution and interactive composer restoration are not established by these tests.

## Android incoming-sync checkpoint on 2026-10-02

- 32 named checks now pass across the three Android instrumentation processes. The first process opens an authenticated WebSocket, observes a message sent by a second user, advances the SQLite-backed resume cursor only with the raw event batch committed, and processes the journal to zero pending/dead entries.
- The incoming message is fetched through the authorized single-message endpoint and accepted by Telegram's update processor. WebSocket credentials stay in the Authorization header; reconnect uses the committed cursor and bounded exponential backoff.
- Successfully processed inbox rows are bounded to the newest1000 per account. Handler failures retry at most8 times and then remain as dead letters so a poison event cannot block later entries.
- This checkpoint does not establish background survival, notification delivery, a forced crash exactly between persistence and application, or complete Telegram model updates for conversation/member/typing/reaction/receipt/pin events.

## Android outbox lifecycle checkpoint on 2026-10-02

- 37 named checks pass across first/offline/restart phases. Additional checks cover exclusive SQLite send claims across two database handles, payload removal for never-attempted cancellation, refusal to report an uncertain send cancelled, recovery after reopening a claimed row, acknowledgement retention and a populated schema1-to2 upgrade.
- The sender claims before HTTP access and recovers interrupted claims using the same client ID on startup. Sent acknowledgements expire after seven days; cancelled rows expire after one day. The schema upgrade preserves queued payloads and treats existing rows as potentially attempted.
- Telegram request-token cancellation and server reconciliation of uncertain cancellations remain incomplete. These tests verify the internal store/sender lifecycle; they do not establish the interactive cancel-message flow.

## Server send-cancellation checkpoint on 2026-10-02

- Ten PostgreSQL integration tests pass, including cancellation authorization, sender isolation, cross-conversation conflicts, repeated cancellation, permanent offline-retry rejection and reconciliation of an already committed message.
- Twenty concurrent send/cancel races each yielded either no message with a committed cancellation fence, or one message with its exact ID returned by cancellation. All existing auth, media, permissions, sync and migration tests also pass; Go package tests and vet pass.
- Android request-token wiring and durable cancellation-intent reconciliation are still outstanding. Cancellation fences have no expiry so old offline replays stay rejected; operational storage limits remain to be added.

## Durable Android cancellation checkpoint on 2026-10-02

- 47 named checks pass across the actual first/offline/restart emulator processes. The scoped HTTPS APK persists cancellation intent while ADB forwarding is removed, survives process death, confirms cancellation after reconnection and rejects a delayed send retry with HTTP409.
- A server-committed send with a lost local acknowledgement reconciles to its original message ID and is not falsely reported cancelled. Confirmed cancellation and committed acknowledgements remove the retained payload.
- Store checks cover cancellation during an exclusive send claim, cancellation superseding a failed send, intent persistence across database reopen, refusal to revive cancelled IDs and populated schema2-to3 migration. Existing schema1 upgrade, ordering, retry, TLS, WebSocket and auth checks also pass.
- The offline retry assertion now waits within a bounded deadline for the durable retry state, instead of assuming a fixed six-second network failure. The committed fixture explicitly records its attempted claim.
- Telegram request-token cancellation wiring and the interactive cancel-message flow remain unverified. These checks exercise the store/sender and real server endpoint.

## Telegram request-cancellation checkpoint on 2026-10-02

- 55 named Android checks pass over first/offline/restart. New checks exercise actual ConnectionsManager dispatch, cancelRequest, bindRequestToGuid and cancelRequestsForGuid hooks with the local HTTPS backend.
- An attempted send cancelled while disconnected persists intent without an onCancelled callback or a send acknowledgement. Token and GUID cancellation survive process death, and both entries become CANCELLED after reconnecting. An account with the same numeric token cannot cancel the originating request.
- Cancelling a server-committed request preserves its original acknowledgement and does not invoke onCancelled. Cancellation before unsupported private-send preparation and cancellation of a read request suppress their completion callbacks.
- The pinned preparation script regenerates ConnectionsManager from the exact upstream commit before applying all hooks, retaining reproducibility across repeated builds. A cancelled row discovered after send-future registration now cancels that future instead of leaving it unresolved.
- These are transport integration tests. Original SendMessagesHelper still performs optimistic local deletion during cancelSendingMessage; deferring that removal until proof, durable UI cleanup and the complete interactive cancel-message flow remain required.

## Durable UI-cleanup journal checkpoint on 2026-10-02

- 63 named Android checks pass across first/offline/restart. The additional store tests exercise populated schema3-to4 upgrade, a UI cancellation journal reopened after an interrupted claim, account isolation, immutable local/dialog/random IDs and transactional rollback on invalid payload.
- Cancellation intent and the original negative UI message identity commit together. Terminal send records remain protected from retention pruning until the matching UI-cleanup record is explicitly acknowledged; a mismatched acknowledgement cannot clear it.
- Already committed sends remain SENT. Missing local delivery history always requires server cancellation proof, including repeated cancellation calls; an existing cancellation intent cannot be downgraded to a locally proven cancellation merely because its HTTP attempt has not started yet.
- The journal is a foundation for the adapter. It is not yet connected to SendMessagesHelper's removal or the Telegram cache worker, so complete interactive cancellation and durable cache cleanup remain open.

## External checks

- Dedicated SSH key authentication to port 3939 was rejected. No server commands ran.
- Initially no Android device existed. A project-local Android35 x86_64 emulator was subsequently installed from verified Google packages; WHPX is usable. Physical ARM-device testing remains unperformed.
- Current process did not contain Cloudflare or final Android signing variables.

## Not yet passed

Full Android interactive UI E2E, complete offline incoming flows, E2EE protocol tests, background push, final release/R8 builds, physical ARM-device tests, external backup/retention/restore, production VPS reboot/DNS/TLS, TURN and load testing. Foreground repository/adapter tests do not prove Persian layout or complete multi-device synchronization.

Integration tests use disposable PostgreSQL schemas and fail on unexpected status codes without printing account tokens or passwords. Test fixtures contain only artificial local data. The local backup dump is ignored and is not an external backup.
