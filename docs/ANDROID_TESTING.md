# Local Android integration

## Shared deletion and cold reopens — 2026-10-08

`scripts/test-two-client-messaging.ps1 -Delete` includes registration, bidirectional text delivery and edit, then native Delete menu cancellation/confirmation, receiver removal in the same process and fresh app-process/native-history entry on both clients. The supported normal-group dialog explains shared deletion without a local-only checkbox; backend/adapter still enforce permissions and server-confirmed deletion. Cancellation verifies actor bubble and unchanged server revision; confirmation verifies same-identity newer tombstone, native entry/bubble absence and absence from live server history. Cold reopens repeat absence checks.

Pass162 recorded checks (registration30 + messaging/edit78 + deletion54), report artifacts/android-two-client-deletion.json; local APK SHA256ba2e92f67985cf877eb272e31d08d7d371f1cc604d4943ae6ba712458ea088c5. Initial test asserted the message-options action boolean, but upstream opens the menu and returns inherited result; corrected to verify actual menu/confirmation instead. Final corrected build48s passes, final test no retry. Does not prove offline/retry/reconnect, physical devices/touch, mutation-interrupted process death or other deletion modes/permission/account race acceptance. Group fixture remains REST-created and ChatActivity entry programmatic.

## Native edit on two clients — 2026-10-08

Run `scripts/test-two-client-messaging.ps1 -Edit` after a local-TLS x86_64 instrumentation build. Uses distinct native account0 registrations, creates a REST group fixture and enters ChatActivity programmatically. Baseline foreground send/receive runs both directions; editor invokes actual message options accessibility action, native Edit menu, waits for preflight, changes native field and clicks Done. Receiver observes the edited bubble in the same live process. Both assert old bubble absent and unchanged server ID/ordinal with a newer revision/edited timestamp, no duplicate or old server text.

Final pass:108 recorded checks (registration30 + messaging47 + edit31), local APK SHA256428aeed1764f09479ba12b55541cb47d39c274154fe8c0848568291f3feb2b3d; artifacts/android-two-client-editing.json. Local build2m49s. Reports remain separate from baseline messaging. Native composer previously called a different generic edit overload than the existing tested revision-bound helper; preparation now routes plain nonscheduled group-text edits to that helper. getMessageEditData handling also added. Counts-only failure diagnostics remain ignored/private. This is emulator UI evidence, not physical touch, phones, deletion/offline/restarts or general edit authorization/unsupported-media acceptance.

## Both foreground delivery directions pass — 2026-10-08

`scripts/test-two-client-messaging.ps1` now passes47 messaging checks plus30 native registration checks on two independent account0 Android35 clients. APK SHA256529a80304327625bf273cab604c76a245454907af3744920bb4ed6bea4072932; report artifacts/android-two-client-messaging.json. This supersedes the earlier sender-bubble failure below for the tested foreground scenario.

The native initial history path requests peer-dialog metadata before history; the shared adapter now returns authorized requested group snapshots instead of rejecting that request. Initial-history completion is checked before receiver readiness. Each direction clicks the real visible/enabled Send view, verifies cleared text, positive confirmed sender bubble and visible incoming recipient bubble, then exactly one server message. Receiver stays in one live process throughout its direction. REST group fixture and programmatic native chat entry remain explicit test setup; physical touch, chat-list/group gestures, edit/delete/offline/retry/restarts and background/phones remain untested.

## Current messaging failure — 2026-10-08

Monitored launchers identified host emulator access violation0xC0000005. `start-emulator.ps1` now uses host GPU with Vulkan disabled; `-GpuMode auto|software` are explicit alternatives, and `-Monitor` keeps a newly started launcher observable until exit. Software mode also crashed here; host mode remained connected through subsequent tests/builds. Reusing an already running AVD does not change its GPU settings. Verify AVD identity before restarting to apply a different mode.

Native group mapping now permits ordinary members to send text; unsupported member media/admin actions have explicit default restrictions. Harness checks native text permission on each client, clicks the actual visible/enabled Send view and intends both directions. This replaces unreliable coordinate injection and does not prove physical touch behavior. Actual native Send clears the composer, but the sender bubble assertion still fails. Latest private diagnostic: server_matches1, native_messages0, matching_native_messages empty. No successful messaging report. Counts/IDs/state diagnostics are copied into ignored .local/chat-diagnostic-*.json before cleanup. Investigate native history/update/render delivery next; do not weaken the bubble assertion or count this REST acceptance as screen delivery.

## Two-client foreground messaging — in progress, 2026-10-08

```powershell
./scripts/build-android.ps1 -Abis x86_64 -LocalTest -Instrumentation
./scripts/test-two-client-messaging.ps1
```

This resets only the two dedicated debug clients, reruns native registration and uses distinct server accounts. REST creates the group fixture; a native request loads group metadata and ChatActivity is opened programmatically. The receiver stays live in its original instrumentation process while the sender touches Send. Intended assertions check visible confirmed outgoing/incoming bubbles and exactly one server message. Fixtures go through stdin and are removed finally. This does not test group creation/chat-list gestures, edit/delete/offline/restarts or physical phones.

Builds pass, but messaging is not yet verified: initial run failed native group loading; after setup adjustment, the peer emulator disappeared in two executions before receiver readiness. Both registration suites and identity/group-fixture phases passed. There is no successful messaging report; use sanitized .local/chat-*.log and actual ADB/process state to diagnose the peer termination before continuing.

## Two independent client registrations — 2026-10-08

```powershell
./scripts/test-two-client-registration.ps1
```

Requires the existing local Core/TLS service and matching x86_64 instrumentation APK. Starts or reuses only the exact `vyrn_api35`/5560 and `vyrn_api35_peer`/5562 AVD pairs, verifies different Android IDs and runs actual invite-registration/recovery-acknowledgement controls on each with separate disposable credentials. Each run resets only that emulator's VYRN debug app. Peer AVD uses1536MB RAM. All connected emulator identities are checked before reuse; unrelated emulators are rejected.

The same app passed15 checks on each device, total30, without attach retries. Per-device logs/reports stay separate; `artifacts/android-two-client-registration.json` requires fresh matching reports and identical APK hashes. This establishes two independent Android clients and account setup. Message sending/receiving/edit/delete, offline/restart behavior and physical phones still need their own evidence. Do not run the live shared-proxy disconnection suite concurrently with another client test.

## Live disconnection on the original session screen — 2026-10-07

```powershell
./scripts/test-android-integration.ps1 -Phases sessions-network
```

Requires the x86_64 local-TLS instrumentation APK and the existing dedicated native localhost proxy: .local/devtls-native.exe, its matching PID file and an HTTP private-IP:8080 upstream. The helper verifies the process path/arguments before stopping it; normal phases continue to support their existing TLS setup. It restores the same proxy/certificate and forwarding in finally. Native-proxy bootstrap portability is not established by this suite.

One live runner coordinates four public step/ack files with the host. Actual Settings/Devices entry, cached-load failure, offline Retry dispatch, initial empty-screen failure after Back/Devices, successful connected Retry and failed offline revocation are verified. Closing the proxy terminates already-open TLS streams; reverse removal alone is insufficient for this live case. Final report95 recorded checks/78 distinct names, no attach retry, includes the normal registration/session UI cases. Fixture/step files are removed afterward. This is local transport loss on one emulator, not two physical phones, mobile network transitions or background acceptance.

## Original session-management screen — 2026-10-07

```powershell
./scripts/test-android-integration.ps1 -Phases sessions-ui
```

Requires a ready local Core/TLS proxy and the x86_64 local instrumentation APK. The script clears only the dedicated test app, creates a disposable invite registration via actual controls, opens Settings programmatically and touches Devices/session rows. It exercises individual and bulk confirmation/cancellation, revoked access/refresh rejection, current-session preservation, hidden unsupported QR/lifetime controls and a controlled unknown-hash error followed by a successful UI retry. Buttons in detail sheets use accessibility; confirmation uses actual native views. Final report:69 recorded checks,57 distinct names, no attach retry. This does not establish physical-device, actual settings-tab entry, offline or complete lifecycle acceptance.

## Recovery code across real process restarts — 2026-10-06

After local TLS setup and an x86_64 local instrumentation build:

```powershell
./scripts/test-android-integration.ps1 -Phases @('onboarding-prepare','onboarding-restart','onboarding-gap','onboarding-recover','onboarding-complete')
./scripts/test-android-integration.ps1 -Phases registration-ui
./scripts/test-android-integration.ps1 -Phases auth-ui
./scripts/test-android-integration.ps1 -Phases @('logout-prepare','logout-offline','logout-ui')
```

Run the five onboarding phases together: only the first clears app data, then each round force-stops the app. Preparation uses actual registration controls, exercises manual/automatic refresh and identity/acknowledgement guards, and saves only public state, PID and a recovery-code hash in app-private test storage. Restart removes the local TLS forwarding and verifies the exact selectable dialog offline. Gap clears native config while retaining encrypted tokens; recovery restores server-confirmed native identity online and presses the actual Saved button. Complete verifies the removed code stays absent and MainTabs opens after another process restart. Finally removes the fixture/state file and restores forwarding.

The final report has45 onboarding checks; the same app passes registration15, recovery/sign-in12 and logout38, total110. Permission-dependent counts can vary. Native syncContacts=false must persist with identity before navigation. This tests one emulator and new registrations; arbitrary crash timing, legacy-format migration, storage/Keystore failures, complete account switching and physical-device acceptance remain open.

For Android's observed old-task-removal race, the harness retries once only when instrumentation reports 'shortMsg=failed to attach', which happens before the runner enters. It saves the first result in ignored .local logs and records startup_attach_retry in the report. Test assertions/application crashes are never automatically retried.

## Native logout and login persistence — 2026-10-06

After the local TLS setup and x86_64 instrumentation build, run:

```powershell
./scripts/test-android-integration.ps1 -Phases @('logout-prepare','logout-offline','logout-ui')
./scripts/test-android-integration.ps1 -Phases auth-ui
./scripts/test-android-integration.ps1 -Phases registration-ui
```

The logout sequence clears only the disposable vyrn_api35 app once, then preserves its account across real force-stops and an offline/online network transition. Do not run logout-ui alone against a fresh app: logout-prepare establishes the disposable account. The current report has11 preparation,8 offline and19 online checks; permission-dependent counts can vary. Online checks touch the original LogoutActivity row and confirmation, exercise a controlled stale-session race, verify server revocation/local clearing and preservation of another session, then touch the actual welcome button and verify login. auth-ui passes11 and registration-ui14 checks on the same app. Reports remain separate.

Use specific screen-state waits rather than unbounded waitForIdleSync after intro interactions: the native intro renders continuously. The fixture allows notification permission and denies observed unrelated photos/audio/contacts requests; denied repeated requests can have a different button identifier, so the helper also recognizes the observed English button label. This does not fix unwanted production permission prompts or establish Persian system-permission acceptance.

Login now commits native identity before continuing. A process restart in the logout sequence confirms the stored account; process death before acknowledging the recovery-code dialog remains a separate unfinished flow. Forced logout, cleanup failure/ack loss, complete session-management gestures and two physical devices are not covered by this sequence.

## Automatic complete-device acquisition — 2026-10-04

The latest network harness passes64 checks (58 initial,6 distinct-process recovery) on APK SHA-2569b0098cd75f40b797ba41a3fc2d1beb2620350f4778e494cd59eff80c732f33a. Automatic prepare reads/validates both complete ready-device rosters, claims only missing sessions and sends actual authenticated copies to two recipient stores plus the sender's other store. Unprepared devices on both users, no extra claims for existing sessions, session-bound/exact retries and accepted retry after sender-other logout pass. All27 Signal storage/restart checks also pass. This uses four isolated stores on one emulator; private UI, offline cold-start key-service construction and lifecycle/retry/verification scheduling remain incomplete.

## Private codec across actual process death — 2026-10-04

scripts/test-signal.ps1 -Network now passes55 checks across two distinct processes (49 initial,6 recovery). After force-stop, the codec reads both encrypted history caches, reuses the pending body without advancing its ratchet, serves duplicates without decrypting again, recovers the acknowledged server ID and delivers an earlier-prepared pending ciphertext out of order once. Report2026-10-04T06:04:28.4933285Z, APK SHA-2567d7172bd86c0f044d260b3c05538f949479f4286429b5092f3b9d598154ea800. Session cleanup and deletion of both app-private test fixture/state files run in finally. State contains public routing/ciphertext and ratchet hashes, not private keys, tokens or decoded text. These are service tests on one emulator; native UI/lifecycle workers and uncertain acknowledgement-loss recovery still require acceptance.

## Actual deletion and learned expiry — 2026-10-04

The latest scripts/test-signal.ps1 -Network passes47 checks on APK SHA-256 be80e544fa068b2d13bbbedc60a747d7d63f4cfc40c09365e2a69f73d072d1e3. DELETE/GET provides the real tombstone and cleared payload. A real five-second conversation TTL drives sender/two-recipient cache expiry, unread-cache sweeping and stale replay guards without further network reads. Actual cache-write failure rolls back receive ratchets. All27 Signal storage/process-restart checks also pass; the275 original regression suite retains its previous recorded APK evidence. This is a codec primitive on one emulator; expiry-worker/native UI/realtime and private-codec process-restart acceptance remain unfinished.

## Authenticated private text codec — 2026-10-04

The latest scripts/test-signal.ps1 -Network run passes39 checks on APK SHA-256 47d8700db1c728e2975083bb6aae9d8c635abc6d5bfdf7e1a03b5b421ee6e0b7. SignalMessages now persists all fanout ratchets and exact outgoing body/own cache atomically, retries unchanged ciphertext, validates authenticated inner conversation/client/revision/participants/sender/recipient/routing metadata, and commits decrypt state with encrypted receive cache. Actual failure/retry/tamper/two-recipient/cache-reopen checks pass. Deletion tests inject a tombstone DTO, so server deletion/realtime acceptance is still required. Private UI, scheduling, explicit verification, rotation, expiry and two physical clients remain unverified. Historical sections retain their original scope.

## Actual private ciphertext service delivery — 2026-10-04

The latest scripts/test-signal.ps1 -Network run passes27 checks. Real Signal ciphertext is sent through POST /v1/conversations/{id}/messages with the signal-v2 envelope, retrieved through authorized GET /v1/messages/{id} and decrypted by the recipient. A later message has distinct copies for two recipient stores, and both decrypt their own copy retrieved through their actual sessions. The second recipient uses the sender's physical device UUID under a different account; authenticated profile identity and separate Signal addresses/storage namespaces disambiguate it. Unprepared/revoked destinations and exact accepted retry after revocation are checked.

The27 storage/ratchet/restart checks and275 existing Android checks also pass on local TLS APK SHA-256 5d69d3798b5c74092dad15eeb48fca25b801284c749ac100b85e5ab8924d3744 against the new local Core. Latest report dates are2026-10-04T04:51:40.2711169Z (network),04:53:30.7645872Z (Signal storage) and04:58:23.2680334Z (original suite). The network report now records service fanout as tested while retaining private_chat_ui as not implemented. Three device stores on one emulator are not two physical Android clients or complete private UI/realtime/outbox/cache/trust/lifecycle acceptance. Historical checkpoint evidence below retains its earlier scope.

## HTTPS Signal key exchange — 2026-10-04

After building the local x86_64 instrumentation APK, run scripts/test-signal.ps1 -Network. It installs only on the named local emulator, forwards the scoped TLS endpoint and feeds disposable generated credentials/bootstrap invite through stdin into app-private storage. The fixture is removed in finally; credentials are never printed or passed as command arguments. It creates two test accounts and a private authorization relationship, publishes real keys and runs20 checks recorded in artifacts/android-signal-network.json.

Coverage includes local acknowledgement-write failure after server commit/recovery, exact publication retry, forged signatures, owner/integer/version validation, consumed-key non-resurrection, exhaustion of100 EC keys with real last-resort-Kyber decryption, account/session changes and session-preserving refresh. Run scripts/test-signal.ps1 separately for27 storage/ratchet/restart checks. Both pass on local APK SHA-256 26bd7401767dba2049405d1137fefb28d0e4ef08f9d6e89e94b5118f5a429127. Do not run emulator harnesses concurrently. Two stores on one emulator and local decryption after HTTPS key exchange are not server ciphertext fanout or full private UI acceptance.
The complete original275-check harness also passes on this APK; the final report is dated2026-10-04T04:29:52.0099938Z. Normal ARM64/ARMv7/Universal Debug builds pass APK verification, but physical installation and release/R8 remain untested. Historical checkpoints below retain their earlier hashes and scope.

Latest Signal run passes27 checks (21 initial,6 restart), including four initial-publication checks for private-key persistence, consumed-key retry, exact store-reopen retry and missing-publication protection. Latest local TLS APK SHA-256: aa56cde1143e184aceea83b48d1a773d1ef244fddabc16b133e5d18a1039beb0. Publication HTTP/UI/refill/rotation remains unconnected. The23-check and275-check same-APK evidence below is the preceding checkpoint, not the current Signal report.

## Signal foundation checkpoint — 2026-10-04

Run scripts/setup-jdk.ps1 for the pinned verified JDK21, then build the x86_64 local TLS instrumentation APK with scripts/build-android.ps1 -Abis x86_64 -LocalTest -Instrumentation. Run scripts/test-signal.ps1 on the local API35 emulator. The harness executes17 initial and6 restart checks, forces application process death and verifies distinct process IDs. It records artifacts/android-signal.json. Private-chat UI and server fanout are explicitly not tested.

These checks use actual official Signal JNI, real keys, PQXDH, ratchets and Android Keystore-encrypted SQLite records. They verify transaction rollback, ciphertext retry persistence, invalid signatures, tamper/replay, identity changes, record binding and missing-key failure. They use two isolated device stores on one emulator, not two physical clients.

The latest original275-check suite and the23-check Signal suite both pass on APK SHA-256 0aa21a915f6de626f3e634fc55bf78e53bb5a9c58db00b8a41e0639a051c6df0. The original harness clears app data; do not run the harnesses concurrently. ARM64/ARMv7/Universal Debug APK verification also passes; physical devices, full UI E2E and release/R8 remain required.

Latest verified checkpoint:275 named API35 checks pass:93 first,34 mutations,76 profile,16 offline,56 restart. The tested APK hash is recorded in artifacts/android-integration.json.

Foreground profile checks use the real distinct session in slot9025 for other-device name/bio edits, avatar replacement, clearing and restoration, then verify a shared-group peer's name/bio/avatar. Actual receiving controller, UserConfig and native SQLite data match; peer profile data survives process restart. The controller preserves existing user and unchanged photo references while applying accepted data. Completed events are drained before controlled empty-cache bio fixtures, preserving all loading/error/Retry assertions. The second device logs out after profile checks. One receiving emulator and API writers are not complete two-Android-device UI acceptance. A native download callback also verifies byte-buffer release after delivery; normal FileLoader download/decoding still passes.

Static profile-picture checks present original SettingsActivity and use ImageUpdater's actual cropped-bitmap entry. They verify no local confirmation before the server, actual JPEG dimensions/current-photo metadata, native full-profile and ImageLocation mapping, original FileLoader authenticated range download/byte equality/bitmap decoding, empty EOF, unsupported video/account rejection and confirmed deletion. UserConfig and native SQLite photo IDs/routing identifier1 survive actual process restart. This does not exercise gallery/camera gestures, old-photo history, derivatives, realtime photo propagation or adversarial lifecycle/disk failures.

The latest profile phase also exercises native blocking/unblocking, immutable target/flag capture at public send entry, server-confirmed block sets/counts/UserFull, paginated block-list metadata and the original PrivacyUsersActivity's actual row/load-error/Retry. A separate authenticated device session changes block state; foreground WebSocket reconciliation updates the receiving controller and native full-profile cache. Restart verifies final unblocked SQLite flags and reloads the server list. Complete offline block-list persistence, concurrent page/late-callback races and popup/gesture acceptance remain unfinished. Slot9025 is reserved for that disposable second-device session.

The suite runs first, mutations, profile, offline and restart in separate app processes. The harness stops the app and waits60 seconds before mutations, profile and restart to respect the unchanged300-request/minute production limit. The profile phase exercises original native updateProfile requests for separate names, partial changes, biography preservation, invalid-input rejection and originating-account protection. It presents the actual original name editor, changes real fields, clicks Done and observes its error dialog and confirmed persistence. The actual biography editor opens without a cached full profile and keeps text/Done disabled until the original controller loads the real biography. A native/vault identity mismatch shows its actual load error; restoring the vault and clicking Retry recovers the real text. Holding the network guard proves no premature save confirmation or cached biography change; successful Done preserves names and confirms native UserFull storage before closing. Real widget tests verify the280-code-point limit for BMP/supplementary characters and selected replacement with a nonzero source offset. The upstream filter's UTF-16 truncation bug is corrected with Character.offsetByCodePoints. Restart checks the full280-emoji server/SQLite biography and UserConfig/SQLite names. Full visual/layout/gesture acceptance, offline load failure, stale profile, photo/realtime, disk-failure injection and adversarial account-switch/late-callback acceptance remain unfinished.

The143-check checkpoint also exercises atomic normal-group deletion through original MessagesController/cache-only removal, permission-denied cache preservation, group binding, admin deletion, idempotent retry, live-history/tombstone handling and process restart. Full screen-driven deletion and prefetched-update/account-switch races remain unfinished. Unsupported delete-for-me/private/scheduled/channel modes are preserved locally rather than silently reinterpreted.

Historical143-check checkpoint: Plain group-text editing exercises the original SendMessagesHelper, exact captured revision, native cache, equal-text ABA conflict, missing cached revision, account/destination binding, deletion and restart persistence. Exact revision metadata now persists in native custom_params; old-cache ABA conflict, legacy decoding, content binding and successful original-helper editing after process restart pass. These checks remain in the current suite. Complete screen-driven composer/reload, rich/media edits and physical-device acceptance remain unfinished.

Use the project-local `vyrn_api35` emulator. These commands reject other devices and keep production credentials, endpoints and signing separate.

```powershell
./scripts/setup-dev-tls.ps1
./scripts/build-android.ps1 -Abis x86_64 -LocalTest -Instrumentation
./scripts/test-android-integration.ps1
```

The isolated Docker project serves HTTPS at `https://localhost:18443`. ADB reverse connects that loopback port to the emulator. Caddy generates a local CA; only its **public** certificate is copied into ignored debug resources. The network security configuration trusts this CA only for `localhost`; certificate and hostname checks stay enabled. No host or Android system trust store is changed. Default builds use production HTTPS and system roots. Release builds reject the local CA flag.

The script generates an artificial account password and reads the existing local test invite privately. It sends the fixture through stdin into app-private storage, never through command arguments. The test removes the fixture and logs only named checks. Accounts and test groups stay in the disposable local smoke database. The test APK is separate from the app APK and contains no credentials.

The runner starts the real LaunchActivity before testing the repository. A headless emulator can restrict DNS for an app running only in the background; launching the Activity resolved the observed EAI_NODATA failure. This foreground integration suite does **not** establish background sync or push behavior.

The suite now passes55 named checks. It covers TLS trust rejection, disabled cleartext, invite registration, Android Keystore session persistence, group delivery, idempotent retries, Persian text, concurrent renewal across separate repository instances, account-bound requests, edit/reaction/receipt read models, ordered message IDs, pagination and the Telegram dialog adapter. It also verifies an authenticated WebSocket resume, a cursor committed with the raw inbox before acknowledgement and successful submission of an incoming message to Telegram's update processor. A second process runs with ADB forwarding removed: two group-text sends remain queued without false acknowledgement, with conversation ordering and retry/account-isolation checks. A third process runs after forwarding is restored: queued sends arrive once and in order; a server commit with a deliberately uncommitted local acknowledgement is reconciled without duplication. Accepted outbox payloads are cleared. Exclusive send claims, safe unsent cancellation, reopened-claim recovery, retention and populated outbox migrations also pass. Durable cancellation survives the offline process, reconciles both absent and already committed sends, and prevents delayed retries from sending cancelled messages. Cancellation during an active send preserves its exclusive claim and supersedes failed-send retries. The suite also exercises ConnectionsManager token/GUID cancellation, account isolation, callback confirmation, committed-send acknowledgement preservation, cancellation before send preparation and suppression of cancelled read callbacks. Results are saved to `artifacts/android-integration.json`.

This is not complete UI E2E: login button navigation, RTL layouts, themes, media, E2EE, background/offline incoming execution, notifications and physical devices still need their own checks. SendMessagesHelper cancellation now waits for durable reconciliation and guarded native cache cleanup; screen-driven cancellation, queued media, crash-between-persist-and-apply recovery and complete event mapping remain unfinished. Instrumentation slots9001/9002/9008/9009 and compatibility account2 are reserved for local fixtures; do not run against a user's populated installation.

Development compatibility: migration003 introduces server-assigned message ordinals. APKs built before this change used fetch-order IDs. Migration of Telegram's previously populated UI cache is not validated; use a fresh development installation when testing this transition. No production upgrade compatibility is claimed. The server migration preserves existing UUIDs, orders their backfill by creation time/UUID, and continues the sequence for new messages; populated migration and repeat-migration tests pass.

Historical schema4 checkpoint: the suite passed63 named checks. Schema4 adds a journal retaining the negative UI message/dialog/random IDs until cache cleanup is durably acknowledged. New checks cover populated schema3 upgrade, journal reopen, account isolation, guarded acknowledgements and retention, immutable UI identity, transaction rollback and conservative handling of unknown delivery history. The journal is not yet connected to SendMessagesHelper/cache cleanup, so these store checks do not prove interactive cancellation completion.

## Owner branding checkpoint — 2026-10-04

Normal public-endpoint Debug builds now support a separate instrumentation APK:

```powershell
./scripts/build-android.ps1 -Abis x86_64 -Instrumentation
./scripts/start-emulator.ps1 -DnsServers '156.154.71.22,4.2.2.3'
./scripts/test-branding.ps1
./scripts/test-public-android.ps1
```

Branding checks reset only the disposable vyrn_api35 app, verify localized names and the original logo mask, then require the real fresh login hierarchy before capturing its screenshot. Inspect artifacts/vyrn-login.png separately. The public connectivity test does not create accounts or reset app data. Its latest result fails at app-UID DNS (UnknownHostException); shell DNS succeeds. This supersedes the older statement that foreground launch alone resolved DNS for every build. Do not count production Android networking as verified.

## Public connectivity correction — 2026-10-05

The earlier app-UID DNS failure is resolved for the foreground test. Android blocked background instrumentation with APP_BACKGROUND. public-connectivity now launches and waits for LaunchActivity before requesting DNS and HTTPS. All eight read-only checks pass after the script force-stops the old process. Platform DNS, certificate validation and cleartext prohibition remain intact; no networking policy exemption was added. This supersedes the previous DNS-failure paragraph and does not verify background connectivity, authentication or physical ARM devices.

## Actual account recovery/sign-in UI — 2026-10-06

```powershell
./scripts/setup-dev-tls.ps1
./scripts/build-android.ps1 -Abis x86_64 -LocalTest -Instrumentation
./scripts/test-android-integration.ps1 -Phases auth-ui
```

The local-only auth-ui phase presses the actual recovery/sign-in controls and fills native fields. It passes10 checks, including wrong-code feedback, successful reset, revoked old session, wrong old password and correct new-password identity. Fixture registration is REST setup, not a registration-screen check. Only the disposable emulator app is cleared; credentials travel privately through stdin and no recovery codes/passwords are logged. Evidence is saved separately to artifacts/android-auth-ui.json. Full account lifecycle and multi-device UI acceptance remain open. The script refuses stale embedded local CAs; run setup before building when the proxy CA changes.

## Registration controls — 2026-10-06

After setup/build as above, run `./scripts/test-android-integration.ps1 -Phases registration-ui`. This clears only the test emulator app and writes artifacts/android-registration-ui.json. All14 checks pass through actual native controls, including real system Home/return, preserving the recovery code until Saved opens the main screen. The code and credentials stay private and are never logged or automatically copied. Existing auth-ui still passes10 checks. Keyboard/touch details, process-death onboarding and physical/multi-device lifecycle are not established by this test.
