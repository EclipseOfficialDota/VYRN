# Verification report

Updated: 2026-10-03. This records performed checks, not planned tests.

## Static native profile-picture checkpoint — 2026-10-03

- All265 API35 checks pass:93 first,34 mutations,67 profile,16 offline,55 restart. The tested local TLS APK SHA-256 is c217393584198dff80ec0ff7f89b1c6489d2cbff81a034a7da1d6c3131317fdf; artifacts/android-integration.json records the actual phases and named checks.
- Instrumentation presents original SettingsActivity and sends a real320x320 bitmap through ImageUpdater's cropped-image entry. Holding the originating account's network guard proves that the native photo reference stays unchanged until server confirmation. The actual server metadata, native ImageLocation, current-photo listing, full-profile photo and Settings response agree. This does not test gallery/camera gestures.
- Original FileLoader downloads the image through authenticated HTTPS ranges. The received bytes exactly match the authorized server download and BitmapFactory decodes the actual dimensions. The first run exposed native download rejection for datacenter identifier0; the single VYRN compatibility origin now uses1 and persists it in native photo structures. No Telegram network endpoint is selected.
- Native unsupported video and wrong-account operations fail without changing the server photo. Native deletion waits for confirmation. A new image then remains in UserConfig and native SQLite, with the correct photo ID and routing identifier, after a real process restart.
- All23 PostgreSQL API integration tests and go vet pass. New avatar coverage verifies valid PNG/JPEG, real dimensions/creation metadata, owner-only unbound uploads, authenticated current-photo access, foreign binding denial, exact partial ranges/EOF, malformed/truncated/oversized image rejection without media rows, and conditional removal. A stale UUID returns409 and preserves the newer replacement; repeated removal of an empty profile succeeds.
- x86 instrumentation and all three normal ARM Debug APK builds pass. The verifier confirms signatures, package, API/ABI and manifest policy with no local test CA in normal APKs. Repository scanner passes123 tracked files.
- Current-photo history is limited to the active photo. Profile realtime, thumbnail derivatives, full gallery/camera/gesture acceptance, adversarial late callbacks/account switches/local storage failures, physical devices and the full original production requirements remain unfinished.

## Native blocking and owned list checkpoint — 2026-10-03

- All246 API35 checks pass:93 first,34 mutations,50 profile,16 offline,53 restart. The tested local TLS APK SHA-256 is1542a77ba6a0b04d5213563fa2eec6a179497a84245bb78f379ce75b67501a38; artifacts/android-integration.json records the actual phases and named checks.
- Original MessagesController blocking and unblocking leave block sets, counts and loaded UserFull unchanged while the account network guard is held. Actual server success changes the native state; repeated blocking preserves the known total. Native block-list slices retain the total on an empty subsequent page and carry real names/dates. Unsupported story/self/page variants and a mismatched account fail before mutation.
- Instrumentation presents the original PrivacyUsersActivity and verifies its blocked-user row. A real native/vault mismatch causes a visible load-error dialog while preserving the cached row. Restoring the vault and clicking the actual Retry reloads successfully. Controller unblocking waits for server confirmation before invoking its completion callback.
- The immutable-request check sends through original public ConnectionsManager, then changes the same request's input peer, flags and story option. The operation still uses captured values. Capturing inside deferred sendRequestInternal was too late; the reproducible source overlay now dispatches at public entry.
- A second distinct authenticated session blocks and unblocks the same peer. The receiving foreground Android instance applies both states through WebSocket reconciliation. Loaded full-profile blocked flags are verified in native SQLite, and the final unblocked cache/list state survives a real process restart.
- Earlier backend verification passed all22 PostgreSQL API integration tests and go vet. The added test reconstructs a populated pre006 block table, repeats migration, preserves unknown old dates, checks owned page/state privacy and bounds, actual new dates, repeat no-ops, directionality and owner-only transactional events.
- Final x86 instrumentation build and all three normal ARM debug builds pass. Signature/package/API/ABI/manifest checks verify all three normal APKs; the local CA is absent from them. Repository scanner passes122 tracked files.
- Complete offline block-list persistence, stale/concurrent pagination, adversarial late UI callbacks/account switches, full popup/gesture acceptance, physical devices and production acceptance remain unfinished. These checks do not establish complete privacy settings or full UI E2E.

## Biography loading, retry and Unicode checkpoint — 2026-10-03

- Original ChangeBioActivity now loads a missing full profile through the original controller. It disables text/Done until the originating user's real biography arrives, displays loading status and shows a localized error with actual Retry/Cancel controls on failure. The controller gains an optional UI-thread failure callback while preserving existing overloads. Fragment destruction removes its observer and cancels its bound requests; view recreation retains the original identity.
- Instrumentation removes the actual controller's full-user cache, opens the real editor while its account network guard is held and proves the controls remain disabled without premature completion. Releasing the guard loads the existing server biography. A real native/vault identity mismatch then exercises the load failure, visible locked controls and unchanged server value. Restoring the vault and clicking the actual Retry button reloads the biography and enables editing. Offline load failure and adversarial lifecycle/account-switch races remain unverified.
- The screen's filter/counter now use the server's280-code-point limit. The boundary test found an upstream CodepointsLengthInputFilter bug: it counted code points but used that count as a UTF-16 substring index, truncating supplementary characters too early. The reproducible overlay uses Character.offsetByCodePoints. Real widget tests cover281 BMP characters,280 supplementary emoji plus an extra character and selected replacement with a nonzero source offset. The final280-emoji biography is accepted by the server and survives native SQLite/process reopen.
- All221 API35 checks pass:92 first,34 mutations,28 profile,16 offline,51 restart. The latest Android report records the tested APK hash. Final x86_64 instrumentation and three normal ARM Debug APKs build successfully; signature/package/API/ABI/manifest verification passes. Scanner passes119 files. Backend unchanged from the preceding21 PostgreSQL tests/vet checkpoint.
- The first instrumentation compilation failed because its cache fixture referenced an upstream implementation dependency not exposed to the test module. The fixture now invokes the existing native cache method reflectively without adding a dependency. The subsequent real boundary failure produced the Unicode correction above; only the final complete221-check run is recorded as passed.
- Full visual/theme/RTL/gesture acceptance, stale profile and late callbacks, disk-failure injection, account switches, photos/realtime, physical devices and the rest of the original production scope remain open.

## Original biography-editor persistence checkpoint — 2026-10-03

- Authenticated profile reads now return actual viewer-specific blocked state and current common-group count. The new PostgreSQL test proves directional blocking, unrelated-viewer isolation and membership removal, without exposing public login fields. All21 API tests and go vet pass.
- Original users.getFullUser maps immutable self/target identity through the existing origin-bound vault guard to a standard users.userFull response. Biography, blocked state and common-chat count come from the server; call capabilities remain disabled. Mandatory peer/notification structures allow the original MessagesController to load and persist the full profile.
- Instrumentation opens the actual original ChangeBioActivity after the native controller loads the real full profile, edits its actual field and clicks Done. Holding the network guard proves unchanged cached biography and disabled Done before server confirmation. Success preserves separate names, writes the native UserFull and verifies its stored value before posting userInfoDidLoad or closing. Actual process reopen confirms the saved biography in both server and native SQLite.
- All212 API35 checks pass:92 first,34 mutations,19 profile,16 offline,51 restart. artifacts/android-integration.json identifies the tested local TLS APK by hash. All three normal ARM Debug APKs build and pass signature/package/API/ABI/manifest verification. Scanner passes119 tracked files.
- The earlier automatic approval review rejected starting the Android build because of account usage limits. Current account limits subsequently allowed execution, and both builds/tests actually completed. The stopped local Core, TLS proxy and emulator were restarted; Core now uses the profile-metadata binary. No production operation occurred.
- Full visual/gesture acceptance, opening the biography editor without an already loaded full profile, disk-failure injection, adversarial account switches/late profile callbacks, profile photos/realtime and the remaining production requirements are unfinished. Physical ARM devices and release signing have not been verified.

## Original name-editor persistence checkpoint — 2026-10-03

- Instrumentation presents the actual original ChangeNameActivity, edits its real fields and clicks its original Done view. A combined overlong name produces its visible error dialog, re-enables retry and preserves local/server names. A valid save while the vault's network guard is held leaves names unchanged and Done disabled; success arrives only after releasing the guard and receiving the server response.
- Success preserves the original native user object/photo, sets the optional first/surname flags, writes the real native user cache and saves the new names. The fixture begins with a cached user whose surname flag is absent, so later serialization must include the newly accepted surname.
- The first real restart found old names in UserConfig despite correct server state and an initially correct native cache. Original saveConfig defers through NotificationCenter.doOnIdle and uses asynchronous preference persistence. The name editor now invokes a narrow, expected-user-guarded serialization/commit under UserConfig's existing lock before reporting success or closing. Commit failure restores the prior local names/flags and explains that the server saved the change but local storage failed. It does not replace the current account with the captured user.
- All205 API35 checks pass:92 first,34 mutations,13 profile,16 offline,50 restart. All original checks remain; the mutation tests now run in a separate stopped/paced process to avoid exhausting the unchanged production request window. Actual process reopen reads the confirmed first/surname from both UserConfig and native SQLite while the server biography/names remain correct.
- Final local instrumentation and all three normal ARM Debug APKs build; APK signature/package/API/ABI/manifest verification passes. Backend unchanged; preceding20 PostgreSQL tests/vet remain evidence. Scanner passes118 tracked files.
- This covers real editor widget/button/error/confirmation paths and process-death persistence. Full visual RTL/theme/tablet/gesture acceptance, adversarial account-switch races, local-disk failure injection, biography/photo workflows and production acceptance remain unfinished.

## Structured profile and partial-update checkpoint — 2026-10-03

- Migration005 adds nullable first_name with legacy display-name fallback, last_name and about. Self/public profiles, discovery and member snapshots expose actual separate names. Partial profile changes serialize under the user's row lock, preserve omitted fields and validate the combined100-character name and280-character biography. Legacy display_name updates reset the separate surname; explicit avatar_id:null removes the photo.
- Fixed the existing name-only PATCH behavior that erased avatar_id. Actual owned-avatar fixtures now survive name and biography changes. Invalid input, unauthorized avatars and mixed legacy/structured names leave the profile unchanged. Concurrent first-name and biography updates both survive.
- All20 real PostgreSQL API tests and go vet pass. The profile test reconstructs a populated pre005 user schema, applies the migration twice and verifies legacy fallback before changing the profile. Public profile/discovery tests still reject exposure of email/admin fields; discovery now allows exactly the five public fields including separate names.
- Original Android account.updateProfile maps immutable request flags/strings through the origin-bound vault guard to the partial API. Separate names persist in domain/native models. The reproducible original ChangeNameActivity overlay saves the native user/cache and closes only after server confirmation, preserves other cached user attributes and binds edits to the identity present when the view opens. Failure leaves local names unchanged and allows retry.
- All196 API35 emulator checks pass:126 first,5 profile,16 offline,49 restart. New checks cover separate/partial names, biography preserving names, invalid input, mismatched identity and fields after real process restart. The full suite initially hit actual HTTP429 when the extra profile calls followed the already heavy first phase; the harness now stops the app and waits60 seconds before its isolated profile phase, as for restart. Production rate limits are unchanged. artifacts/android-integration.json records the tested APK hash.
- Final local instrumentation and all three normal ARM debug builds pass; APK signature/package/API/ABI/manifest checks pass. Local Core was replaced after exact PID/executable verification and applied the additive migration. Scanner passes118 tracked files.
- Native request/model persistence is tested; full screen-driven ChangeNameActivity success/failure/cache restart, biography screen/loading, profile photo upload/rendering, profile realtime propagation, adversarial account-switch races, physical devices and production acceptance remain unfinished.

## Bulk device-session revocation checkpoint — 2026-10-03

- DELETE /v1/sessions revokes every other owned session with one PostgreSQL UPDATE, preserving the authenticated caller and other users. Repeated calls return204. Original Android auth.resetAuthorizations uses this endpoint under the same originating-account guard as session listing and individual revocation; it does not loop over client-fetched sessions or report partial success.
- All19 PostgreSQL API tests and go vet pass. The added test establishes three owned devices, denies unauthenticated revocation, connects both other devices through actual WebSockets, revokes both and observes policy-violation closure. Their access and refresh tokens fail; the caller can still refresh, an unrelated user remains authenticated, repeated revocation succeeds and a new device can log in afterward.
- All190 Android API35 checks pass:126 first,16 offline,48 restart. Five additional checks establish three real sessions through login, invoke the original native bulk request, prove access/refresh rejection on both other devices, preserve the current and unrelated users and repeat the operation. artifacts/android-integration.json records the tested APK hash.
- Local x86_64 instrumentation and all three normal ARM debug APKs build successfully. APK signature/package/API/ABI/manifest verification passes. The local WSL Core was replaced only after verifying its exact existing executable and PID; readiness and the Android suite confirm the new endpoint runs.
- Full rendered session-screen acceptance, callback/account-switch races at final UI dispatch, configurable lifetime, authenticated logout/recovery, physical devices and production deployment remain unfinished. Concurrent new logins after revocation are permitted; this operation does not ban future devices.

## Native session management checkpoint — 2026-10-03

- Android dispatch maps original account.getAuthorizations and account.resetAuthorization to the authenticated VYRN session API. The original SessionsActivity receives actual device names, creation/activity timestamps and the server's current-session flag. Unknown OS, application-version and IP/location data remain empty. The displayed30-day lifetime matches the fixed server refresh lifetime; changing it is not implemented.
- Stable native hashes are scoped to the originating user. Dispatch captures native self identity, validates it against the authenticated vault under the shared account lock and rechecks identity before callback delivery. Unknown hashes, identity mismatches and terminating the current session through the other-device endpoint fail closed.
- Nine new emulator checks pass: two real sessions, metadata/current flag, repeated stable hashes, current/unknown-session rejection, mismatched account rejection, actual second-device revocation, access/refresh rejection on the revoked device and preservation of the current session. The suite passes185 checks:121 first,16 offline,48 restart.
- Normal ARM64/ARMv7/Universal debug builds and APK policy/signature verification pass. The reproducible session-sheet overlay hides call/private-E2EE capability switches while those features remain unavailable. Full screen-driven acceptance, late UI callback/account-switch races, all-other-device revocation, lifetime changes, logout and recovery remain unfinished. Backend code is unchanged.

## Existing native-cache normalization checkpoint — 2026-10-03

- All176 API35 Android checks pass:112 first,16 offline,48 restart. artifacts/android-integration.json records the tested APK hash.
- App-private deletion table/triggers are installed during native database opening, after upstream table creation/migration and before publishing the deletion snapshot or loading cached content. Startup purges messages_v2 rows with existing fences and repairs their dialog tops. New deletion no longer performs schema installation; its batch insert and snapshot refresh retain the existing acknowledgment order.
- The offline phase creates a genuinely populated legacy fixture in inactive native account3: persisted confirmed deletion fence, all six deletion triggers missing, a stale deleted message/dialog top and a separate live cached message. Actual native putDialogs/getMessage prove both rows exist before process death. The fixture contains normal sent text and does not activate that account or configure its authentication.
- After real process restart, account3 remains unauthenticated. Native database opening removes the fenced row, restores the fence snapshot and preserves the live body without a new authenticated deletion event. A stale native writer remains blocked and its dialog top/date stay zero. Running the initializer twice again preserves those properties and the live row.
- Local instrumentation and normal ARM debug builds pass; all three normal APKs pass signature/package/API/ABI/manifest verification. Backend unchanged; preceding18 PostgreSQL tests/vet remain evidence. GitHub connector lookup still finds no VYRN repository under EclipseOfficialDota; callable tools still lack repository creation.
- This verifies missing-guard normalization for a populated cache with known persisted fences. It does not establish arbitrary upstream-version migration, malformed-database recovery, legacy fetch-order identifier migration, media-cache deletion or full rendered/multi-account/production acceptance.

## Deleted dialog-top replay checkpoint — 2026-10-03

- All170 API35 checks pass:112 first,15 offline,43 restart. Evidence: artifacts/android-integration.json with the tested APK hash.
- Persistent native triggers repair dialogs.last_mid/date when a confirmed deletion removes the top, or when a stale INSERT/UPDATE writes that fenced top later. They choose the newest surviving cached message, excluding every recorded fence, or zero for an empty cache; last_mid_group is cleared for the replaced top.
- Both original processLoadedDialogs and processDialogsUpdate filter their message arrays again at final UI application. A fenced incoming top is replaced by the newest surviving incoming/current message or zero. A fenced previous top cannot prevent the corrected lower/empty top from replacing it. Pending IDs remain eligible for the dialog preview.
- Actual putDialogs tests verify persisted empty last_mid/date; both native UI processors reject the serialized prefetched top initially and after restart. A live remaining message is preserved in both native SQL metadata and the controller preview. Controlled UI holds also process a stale dialog response before real deletion, then require the released UI to omit the deleted top/body for both new/edit races.
- Single-dialog fixtures use the upstream's actual single-dialog mode (count1/loadType3). Treating those fixtures as full pages activated extra native pagination and caused the initial request failure. The corrected full suite passes. The test reads the native dialog through its public accessor and inspects its internal message collection with reflection, without a test-only production hook or extra dependency.
- Local instrumentation and normal ARM debug builds pass; all three normal APKs pass signature/package/API/ABI/manifest verification. Backend unchanged; preceding18 PostgreSQL tests/vet remain evidence.
- Full rendered preview/badge acceptance, a surviving fallback absent from incoming/current UI state, large conversations and private/channel/scheduled/album behavior remain unfinished. Native migration/recovery and installation/repair for older caches before their next authoritative fence also remain unverified; this is not complete production upgrade acceptance.

## Nonblocking native deletion snapshots — 2026-10-03

- All160 API35 Android checks pass:107 first,15 offline,38 restart. artifacts/android-integration.json records the tested APK hash.
- MessagesStorage publishes an immutable volatile snapshot of persisted uid/mid fences after opening/migrating its database, and refreshes it after confirmed fence insertion before acknowledging deletion. Cleanup/reopen invalidates the prior snapshot. A failed reload invalidates readiness; an existing ready snapshot stays available during a successful replacement.
- Native update/UI/history checks now read this snapshot without waiting on storage. Only background callers can wait for initial native database readiness; UI and storage-queue callers return unavailable if initialization has not completed.
- The independent history-dispatch check holds the real native storage queue behind a latch. The filtered history notification must arrive within three seconds while that latch is still held, remove the deleted positive row and preserve the pending negative row/requested count. This passes in the initial phase and after real process restart. The existing update-before-deletion UI queue races continue to pass.
- Local instrumentation and normal ARM debug builds pass. All three normal APKs pass signature/package/API/ABI/manifest verification. Backend unchanged; preceding18 API tests/vet remain evidence.
- This proves this deletion guard does not wait for a busy storage queue. Whole-screen responsiveness, large deletion-ledger memory/reload cost, account switches, database recovery/migration and late dialog-list metadata still need broader acceptance tests.

## Deletion at final UI application checkpoint — 2026-10-03

- All158 API35 checks pass:106 first,15 offline,37 restart. Evidence: artifacts/android-integration.json with the tested APK hash.
- Native updateInterfaceWithMessages checks persistent deletion fences before changing dialog state or emitting new messages. The native edit UI loop checks again before changing cached dialog objects. NotificationCenter checks new/edit/history lists at actual dispatch, including re-dispatch after animation deferral; pending negative IDs are retained and an empty history page still completes its load event.
- Deterministic tests hold the actual UI queue, submit a live new/edit update through the original processor and prove it was not a deletion update at entry. A real authenticated server deletion then reaches the durable native fence while the UI is held. Releasing the UI must produce no matching new/edit message notification and leave no cached row. Both cases pass after real process restart.
- Independent native messagesDidLoad dispatch tests remove a deleted positive row while retaining a pending negative-ID row and preserving the original requested count. Both initial and restart dispatch checks pass. This is notification-path evidence, not a complete interactive/history-network acceptance test.
- Intensive extra queue checks were moved into the fresh restart rate-limit window after HTTP429 in the first phase; server rate limits remain unchanged. Later registration failures were traced to an exhausted local test invite. Only that invite, matched by a privately computed digest, was replenished in local PostgreSQL; no token or digest was printed and no production operation occurred.
- Local instrumentation and normal ARM debug builds pass. All three normal APKs pass signature/package/API/ABI/manifest verification. Backend unchanged; preceding18 PostgreSQL tests/vet remain evidence.
- Full rendered chat/dialog acceptance, late dialog-list metadata, account-switch and native database recovery/migration tests remain unfinished. Queue-based UI fence reads have not been benchmarked under storage contention; this checkpoint does not establish complete production UI performance or sync acceptance.

## Confirmed deletion at native update entry — 2026-10-03

- All154 API35 Android checks pass:105 first,15 offline,34 restart. artifacts/android-integration.json records the actual tested APK hash.
- The pinned original MessagesController.processUpdateArray now reads persistent deletion fences before applying positive-ID group new/edit updates. Fenced live updates become standard delete updates; no new wire constructor or synthetic PTS is introduced. Missing/unavailable cache verification returns false for retry. Calls already on the storage queue read directly, avoiding a self-wait.
- Tests submit a real prefetched live message through the original native update processor as both new and edited after confirmed deletion, and repeat with its saved serialization after real process restart. The native messagesDeleted notification must occur; matching didReceiveNewMessages/replaceMessagesObjects notifications must not occur, and the cached row must remain absent. Existing live incoming/edit tests continue to pass.
- Local instrumentation and all three normal ARM debug builds pass; signature/package/API/ABI/manifest verification passes. Backend unchanged; preceding18 PostgreSQL tests/vet remain evidence. The harness keeps the app stopped for the existing rate-limit window before restart recovery.
- This closes replay entering the processor after the fence is recorded. It does not prove the race where processing starts before deletion and a queued UI operation runs later, or late history/dialog responses. Final UI application guards, full screen-driven acceptance, account switches and database recovery/migration remain unfinished.

## Durable native deletion cache checkpoint — 2026-10-03

- All150 API35 Android checks pass:103 first,15 offline,32 restart. Evidence: artifacts/android-integration.json with the tested local TLS APK hash.
- Confirmed batch deletion, authoritative realtime tombstones and deleted-send reconciliation persist a uid/mid fence in the native SQLite database before acknowledging cache cleanup. A single INSERT statement records the selected batch; an AFTER INSERT trigger removes matching messages_v2 rows. Persistent BEFORE INSERT/UPDATE triggers reject later stale writes without nesting native transactions.
- A live native message fetched before deletion is submitted through the actual array, history and replacement storage APIs after confirmed deletion. All three leave its row absent. The same serialized prefetched message is submitted after real process restart; all three again leave it absent. A stale positive row is also rejected offline while negative pending rows remain available for cancellation reconciliation.
- The initial offline fixture expected a deleted positive row to be stored again; it now explicitly asserts rejection. Later runs exposed HTTP429 at restart session verification. The harness now allows the existing 300-request/minute window to expire with the app stopped before recovery; the production limit is unchanged. Failure diagnostics expose only the numeric HTTP status, never exception bodies or credentials.
- Local instrumentation and normal ARM debug builds pass. All three normal APKs pass signature/package/API/ABI/manifest checks. Backend source unchanged; the preceding18 PostgreSQL tests and go vet remain the backend evidence.
- This verifies messages_v2 persistence, not every UI race: prefetched live update arrays, dialog metadata and visible history can still require filtering at their final application points. Full screen-driven deletion, account switches, unsupported deletion modes, media cache cleanup and durable offline deletion remain unfinished. The native table/triggers' behavior through database recovery/migration is not yet acceptance-tested.

## Authorized native group deletion checkpoint — 2026-10-02

- All18 PostgreSQL API tests and go vet pass. Atomic batch tests exercise mixed sender denial without partial changes, outsider/removed-member denial, cross-group and missing IDs, input bounds, owner/admin permissions and retries without extra revisions/events.
- A real held conversation lock and pg_blocking_pids prove the HTTP deletion is waiting while admin demotion/removal commits. Deletion then returns403/404 and preserves the message, verifying the fresh post-lock membership read.
- Live-history tests prove deleted rows do not consume a page's LIMIT, deleted UUID cursors preserve ordinal pagination, default history/targeted reads retain tombstones, outsiders are denied and expired content is excluded.
- All143 API35 Android checks pass:100 first,14 offline,29 restart. Original MessagesController deletes only accepted messages from native cache; shared denial handling preserves both selected native rows. Account/group-bound atomic requests, unsupported delete-for-me preservation, idempotent retry, admin deletion of another sender, empty live history/dialog top and restart persistence pass. Evidence: artifacts/android-integration.json and its tested APK hash.
- The first Android run failed native_deletion_survives_restart. Native history/dialogs included tombstones and replayed message-created/edit references could map current deleted state into blank live messages. Native feeds now request live-only pages and current deleted references produce delete updates. The complete suite passed after the repair.
- Local x86_64 instrumentation and all three normal ARM Debug APK builds pass. ARM signatures/package/API/ABI/manifest verification passes; physical installation is untested. The running local WSL Core was replaced only after exact PID/executable validation; no VPS mutation occurred.
- Full screen-driven deletion, prefetched live updates racing deletion, account switches, durable offline delete intent and delete-for-me/private/scheduled/channel modes remain unfinished. This checkpoint does not establish complete deletion/sync acceptance.

## Persisted native edit provenance checkpoint — 2026-10-02

- All132 named API35 checks pass:90 first,14 offline,28 restart. artifacts/android-integration.json records the actual tested APK hash. The original SendMessagesHelper successfully edits a restored native cached message after process restart using its persisted exact revision.
- Older cached equal-text provenance returns a real409 revision conflict despite newer history observations. Legacy local parameter blobs preserve their original fields and clear orphan provenance; altered cached bodies cannot reuse a snapshot. The latest authoritative revision is verified in the persisted native row before process death.
- The first run failed at latest_edit_provenance_persisted: native edit storage merged the previous custom_params blob into the incoming message and replaced the new snapshot with the old one. Corrected both edit-cache merge and replaceMessageIfExists to retain incoming provenance without copying an old basis to unknown incoming content. The complete suite passed after the repair.
- Local x86_64 debug/instrumentation build passes. All three normal ARM Debug APKs built successfully and passed signature/package/API/ABI/manifest verification. Physical-device and screen-driven composer/reload/conflict-alert acceptance remain unfinished. Backend source unchanged.

## Native plain-text edit checkpoint — 2026-10-02

- All126 named API35 Android checks pass:86 first,14 offline,26 restart. Evidence: artifacts/android-integration.json with the tested APK SHA-256. The original SendMessagesHelper dispatched an edit using a real foreground fragment; actual server revision/text/date and serialized native cache agree.
- A second-device-style edit away from and back to equal text advances the server revision. The originally captured composer basis still returns409 revision_conflict despite a newer mapped history observation. No newer revision is substituted during save.
- Cached messages without immutable provenance require reload. Requests bound to another account/destination fail without mutation; deleting the message during editing prevents resurrection. Edited native text/date survives process death.
- Local x86_64 debug/instrumentation builds pass. All three normal ARM Debug APKs built and passed signature/package/API/ABI/manifest verification. Physical installation is untested. Backend source unchanged; fifteen API tests and go vet remain verified at the earlier checkpoint.
- Initial test failures exposed a missing compile-time FragmentActivity boundary and a test reading the blocking getMessage API on its own storage queue. Fixed the instrumentation to resolve the real fragment at runtime and call getMessage from the instrumentation thread. The complete suite passed after both repairs.
- Full screen-driven composer/reload and conflict-alert acceptance, rich/media edits and concurrent account switches remain unfinished.

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
