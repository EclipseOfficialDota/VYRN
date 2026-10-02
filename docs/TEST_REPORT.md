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

## Telegram cache/UI cancellation checkpoint on 2026-10-02

- All71 named checks pass across the real first/offline/restart emulator processes over scoped HTTPS. The test calls the original SendMessagesHelper entry point with cached pending group-text messages; it does not drive a screen tap.
- Pending UI rows remain visible while offline. After process death and reconnection, confirmed cancellation removes its negative native row; a previously committed send replaces its pending row with the original positive server ID and body. The journal is acknowledged only after verifying the native cache mutation.
- A failed restart exposed Telegram's native missing-auth-key logout clearing VYRN's independent user. The reproducible ConnectionsManager overlay ignores native onLogout; explicit callback isolation and native identity restoration pass without weakening account or random-ID guards.
- Complete screen-driven cancellation, account-switch races and reconciliation of a committed message subsequently deleted on the server remain unverified.

## Deleted committed-send recovery checkpoint on 2026-10-02

- All73 named emulator checks pass over first/offline/restart. A third cancellation fixture commits a send, deletes it on the server, then seeds both a pending negative native row and a stale positive native row while offline.
- After real process death and reconnection, authorized reconciliation observes the server tombstone and removes both local rows without inserting an empty replacement message. The original SENT acknowledgement and server ID remain intact; cache verification precedes journal acknowledgement.
- This covers an already-deleted committed send. Concurrent deletion after a reconciliation fetch, screen-driven cancellation and account-switch races still require verification.

## Native discovery/group-creation checkpoint on 2026-10-02

- All79 named Android checks pass over real first/offline/restart processes. ConnectionsManager contact search returns a Persian display-name match with a stable member ID; mapped user names survive native TL serialization.
- The suite calls the original MessagesController.createChat and observes chatDidCreated. The returned group is cached with its Persian title, owner flag and two-member count; the invited member reads a message sent through the Telegram send adapter. The native cached group survives actual process death.
- Eleven PostgreSQL API integration tests pass, including authenticated discovery, case-insensitive/Persian matching, literal wildcard handling, result/input bounds, exclusion of self/disabled users, blocks in either direction and absence of login fields. Security/update unit tests and go vet also pass.
- These tests exercise the native request/controller path, not screen taps. Empty contact lists still rely on global display-name search. Group settings/member management, creation with a nonzero auto-delete period, interrupted-create acknowledgement/retry, and complete UI acceptance remain unfinished.

## Creation-time group auto-delete checkpoint on 2026-10-02

- All82 named Android checks pass. The original MessagesController creates a group with a one-day period; the first message has the exact server expiry interval, the dialog adapter exposes the native TTL field/flag and the period survives real process death.
- Twelve PostgreSQL API integration tests pass. The new test verifies zero/minimum/maximum periods, first-send expiry, matching creation/member/recipient-event state and rejection of invalid TTL/member inputs without orphan conversations. go vet passes.
- Creation-time group TTL is now atomic. Screen-driven settings, changing group TTL from native settings, per-message countdown visuals and invited-device realtime TTL mapping remain unfinished; these checks do not establish complete auto-delete UI acceptance.

## Full-group-information checkpoint on 2026-10-02

- All86 named Android checks pass. The original MessagesController.loadFullChat observes a full group containing the actual owner/admin participant types, admin join timestamp and one-day auto-delete period. Native full-group data is serialized into the existing Telegram cache and survives real process death.
- Thirteen PostgreSQL API integration tests pass, including authorized role/date/TTL snapshots, unauthenticated/outsider denial, revoked-member denial and targeted lookup after501 newer groups exclude the old group from the500-item list. go vet passes.
- Native full-group loading and read-only role display are tested through controller entry points, not screen taps. Member-management mutations, native permission controls, group photos and complete screen acceptance remain unfinished.
- A temporary automatic approval-review usage-limit failure prevented the first debug build; usage availability was rechecked after reset and the same reviewed action completed. No approval bypass was used.

## Native group-rename checkpoint on 2026-10-02

- All89 named Android checks pass. The original MessagesController.changeChatTitle renames the Persian group, native cache verification runs after queued writes, the invited member sees the server title with the one-day auto-delete period preserved, and the new cached title survives real process death.
- An initial rename check raced the native storage queue. Ordered cache verification and a standard updateChat metadata notification resolved the failure; the complete first/offline/restart suite then passed.
- Fourteen PostgreSQL API integration tests and go vet pass. New checks cover independent title/TTL patches, invalid/empty patches, ordinary-member denial, explicit zero to disable TTL and five actual concurrent rename/TTL pairs without lost updates.
- All three ARM Debug APKs rebuilt successfully for the rename checkpoint; the verifier passed signatures, package, API levels, ABI contents and manifest policy. Physical-device installation remains untested.
- This covers controller-based rename and local persistence. Screen interactions, permission controls, invited-device realtime metadata, other group mutations and complete UI acceptance remain unfinished.

## Native history TTL checkpoint — 2026-10-02

- All94 named Android checks pass across first/offline/restart on the API35 emulator. Original MessagesController.setDialogHistoryTTL exercises enable, disable and re-enable; standard native update flags and the serialized full-group cache agree with server settings.
- The Persian group title stays unchanged, new messages use the new3600-second expiry and settings/full-group cache survive process restart.
- Initial run stopped at the existing membership timestamp assertion. Emulator/backend clocks differed by two seconds. The revised assertion compares every native participant date directly to the authoritative member snapshot, without relaxing the date comparison.
- TTL cache checks wait for the UI handler to schedule storage writes. Pinned-source preparation removes optimistic local TTL mutations before server acceptance.
- Backend code is unchanged; fourteen API tests and go vet were verified at the preceding partial-settings checkpoint. All three ARM Debug APKs rebuilt; signature, package, API, ABI and manifest verification pass. Native denial feedback, invited-device realtime, countdown visuals, interactive screen acceptance and physical devices remain unverified.

## Foreground group metadata checkpoint — 2026-10-02

- All98 named Android checks pass across first/offline/restart. An authenticated other-member repository emits three actual settings mutations (rename/7200 seconds, zero to disable, restore name/3600). The receiving Android WebSocket cursor/inbox, serialized chat title and full-group TTL flags/cache agree after each mutation.
- Standard native update processing preserves actual creation dates and waits for UI scheduling and native storage before acknowledging processing. This covers one emulator receiving another account's API changes; two physical Android devices and full interactive multi-device acceptance are not tested.
- Earlier native creation/full-group/rename/TTL, cancellation and process-restart regressions pass. Backend unchanged; fourteen API tests and go vet verified at the preceding partial-settings checkpoint. All three ARM Debug APKs built successfully; signature, package, API, ABI and manifest verification pass.
- Conversation creation/member events, stale updates after loss of membership, account switches during queued native updates and full UI crash recovery remain unfinished.

## Native membership event checkpoint — 2026-10-02

- All103 named Android checks pass across first/offline/restart. Real API demotion/promotion/add/remove mutations reach the receiving native chat/full-group cache via WebSocket; counts, roles and serializable member/admin dates match the authorized server snapshot after each change.
- Initial and intermediate runs failed at membership_native_role_date: the assertion required a persisted owner date, but the pinned Telegram creator wire object has no such field. The final assertion checks owner zero and exact dates for other roles. A separate ordering change refreshes loaded participants before native TTL storage and avoids redundant full-chat requests for membership events.
- Earlier name/TTL, outbox, cancellation and restart regressions pass. Backend unchanged; fourteen API tests and go vet retain their preceding verified checkpoint. All three normal ARM Debug APKs built successfully; signature, package, API, ABI and manifest policy verification pass.
- This is one receiving emulator with authenticated repository mutations. Current-account removal, stale events after authorization loss, native member-management screens/permissions and complete two-device UI acceptance remain untested.

## Invitation role preservation checkpoint — 2026-10-02

- All15 PostgreSQL API integration tests and go vet pass. New coverage verifies authorized existing-member invitation as a no-op, owner preservation, ordinary-member denial, admin invitation of a new member and five actual concurrent HTTP invite/promotion pairs without losing admin status.
- preserve_existing is evaluated under the same conversation row lock used by role updates. Existing explicit role changes retain their behavior.
- No Android code changed at this checkpoint. Its latest evidence remains103 named emulator checks and three verified normal ARM Debug APKs from8d02008. The running local Core has not yet been replaced; native management controller wiring remains unfinished.

## Native member management checkpoint — 2026-10-02

- All109 named Android checks pass across first/offline/restart on API35. Original MessagesController.addUserToChat and setUserAdminRole add a real member and promote/demote/re-promote it; native callbacks, actual server roles and receiving serialized cache agree.
- Repeated native invitation preserves admin status; transport-level owner demotion returns403. deleteChatUser with revoke_history=false removes the other member and returns standard participant updates. Original removal controller requests revoke_history=true and remains unavailable; no unsupported flag is silently discarded.
- Nonzero forwarded-history count, current-account removal, history revocation, interactive screens/admin-rights rendering and complete two-device acceptance remain unfinished.
- The running local WSL Core was replaced with the previously tested invitation-preserving binary after exact executable/PID validation. Backend source unchanged; fifteen API tests and go vet were verified at the preceding checkpoint. All three normal ARM Debug APKs built successfully; signature, package, API, ABI and manifest policy verification pass.

## Observed message revision checkpoint — 2026-10-02

- All117 named Android checks pass across first/offline/restart. Actual server edit revision/date reaches the repository and native edited flag; the observed sender/group/body revision matches the mapped message.
- A populated schema2 ID database upgrades to schema3 without changing message IDs. Unknown old revisions require reload; newer observations survive reopen, older ones cannot downgrade them, wrong sender/group/old text is rejected, and a same-revision computed expiry tombstone is not revived.
- Observations store SHA-256 rather than another plaintext copy and serialize read/validate/write in a SQLite transaction. Tests verify the populated upgrade and reopened state; concurrent-helper races remain to be exercised.
- Native edit requests, immutable composer-entry revision capture (including equal-text ABA cases), conflict presentation and complete interactive editing remain unfinished. Backend unchanged; fifteen API tests and go vet retain their earlier verified checkpoint. All three normal ARM Debug APKs built successfully; signature, package, API, ABI and manifest policy verification pass.

## External checks

- Dedicated SSH key authentication to port 3939 was rejected. No server commands ran.
- Initially no Android device existed. A project-local Android35 x86_64 emulator was subsequently installed from verified Google packages; WHPX is usable. Physical ARM-device testing remains unperformed.
- Current process did not contain Cloudflare or final Android signing variables.

## Not yet passed

Full Android interactive UI E2E, complete offline incoming flows, E2EE protocol tests, background push, final release/R8 builds, physical ARM-device tests, external backup/retention/restore, production VPS reboot/DNS/TLS, TURN and load testing. Foreground repository/adapter tests do not prove Persian layout or complete multi-device synchronization.

Integration tests use disposable PostgreSQL schemas and fail on unexpected status codes without printing account tokens or passwords. Test fixtures contain only artificial local data. The local backup dump is ignored and is not an external backup.
