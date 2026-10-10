# Local Android integration

Same-APK security regression Signal61980 exits0 with221 checks (160 network/JNI,44 disconnected cold,17 online restart) at09:56:11 on1fc2ff226ad2e505684a6ce8817d7412ef24a65b9965bdd8aee2bf89583c5946. Normal27784 build exits0 in2m37s; all3 signature/package/API/ABI/manifest verifiers pass. ARM64/ARMv7/universal Debug APKs122477389/108190695/158702806 bytes; hashes b34491d5e3377fb9b5e9d84def0c7f0a46b8aebc6304898989299bddfae31ba3 /5599056447e8a755e7e0a67780089a45448c6227114308f823be5262d5dbf360 /c7583dbe417bb426808c3a1edcc1a0b0ef8cff6f730b4f94059effac6c0bf1b1. Android8.0 minimum, normal public TLS/no local CA; physical installation unproved. No source behavior changes after builds; full original goal remains active.


## Actual private composer offline Send — 2026-10-10

`scripts/test-two-client-messaging.ps1 -PrivateComposer` passes315 recorded checks at09:53:27 on local APK1fc2ff226ad2e505684a6ce8817d7412ef24a65b9965bdd8aee2bf89583c5946 (exec47333 exit0). Both clients register through native UI and retain their original acknowledged devices across cold restart. Peer profile setup does not create the first private pair. Remove transport before clicking the actual native Send control: an injected encrypted-store failure preserves the draft and permits retry; durable success rejects a second click, preserves newly typed text in the first direction and clears the unchanged draft in the second. Pending text/body/ratchet checks, cold offline bubble/draft/native database confidentiality, and cold automatic recovery with one original server/native message pass in both directions. Both receivers and their offline cold caches pass the existing private UI/privacy/draft checks.

First private history failure now releases the native initial-loading state and delayed notifications instead of indefinitely hiding the staged bubble. Send enabled/alpha refreshes when busy/readiness/effect state changes. Unsupported shared plaintext/effect/forward/screenshot/long-press paths remain blocked. A legacy schedule/silent hint is still visible and needs removal for unsupported contexts.

This proves two emulator clients using programmatic native `performClick`, not physical touch or phones. New staged Cancel/Retry/account/navigation/unknown-ACK races, draft ABA/crash-consumption, full device/trust lifecycle, background notifications, media and release/external backup remain acceptance work. Same-APK Signal security regression and normal phone packages are recorded separately after terminal validation.

## Offline private intent without a server conversation ID — 2026-10-10

Same-APK -PrivateAutomatic regression passes318 checks at08:50:05, exec79417 exit0 (registration30 + rounds288). This retains the older prepared-path worker/offline/cold/native receive/draft/gate evidence, separately from the new null-conversation component checks.

Signal network suite219 checks pass at08:44:02 on APKea9e117be87d5b90e3c8cac3772effda2246e05f042e4554a87ff2a28362f0c9. The three actual processes pass160 initial network/JNI,42 disconnected cold and17 online restart checks. The disconnected phase stages a new encrypted intent with a null conversation/body, repeats the exact identity, rejects caller-supplied conversation rebinding and verifies actual failed offline resolution leaves identity/text/body/ratchet unchanged. It also persists cancellation against a null-conversation intent.

After force-stop/reconnect, the original intent resolves through the existing unique private-pair endpoint, decrypts at the recipient and retains its local/random/date identity. Duplicate send retains server ID/ciphertext/ratchet. Cancellation resolves the same pair, fences/scrubs the original client without encryption and cannot be revived. This fixture deliberately reuses an existing server pair while the outgoing intent has no conversation ID; creation of a previously nonexistent pair, resolution-write-failure/crash windows and actual native composer/worker staging remain acceptance work. It is not a two-physical-phone or full private-composer claim.

## Fresh offline private intent — 2026-10-10

Signal network suite209 checks pass08:23:51 on APKa915ae78da9b5f74c2e4b1da7da7800f03dab261d58d4571bb2c9020a5ec26b4:160 network/JNI,37 disconnected cold,12 online restart. Unlike the earlier prepared-before-disconnect fixture, the disconnected phase creates a genuinely new encrypted local intent with null body and unchanged ratchet. Assert exact repeat/native-row/text/peer conflicts, metadata-only recovery, encrypted store reopen and original cancellation before any preparation/directory call. Online cold phase retains the tuple/date/schedule, prepares through current membership/device validation, delivers/decrypts, retries without ciphertext/ratchet changes, fences cancelled work and rejects a mismatched peer/conversation without encryption.

Same-APK -PrivateAutomatic regression passes318 checks at08:30:55 (registration30 + rounds288), exec46982 exit0. Both actual offline app workers and cold-reconnect native deliveries preserve original prepared ciphertext/server/native identity, receiving/draft/history/send-gate checks. This is regression coverage for the existing prepared path; it does not exercise new staged text through the native composer/adapter.

New staging coverage is through the existing journal/send API, not actual composer Send or the new adapter/worker staging entry. A known private conversation and previously acknowledged original device exist before going offline. First-dialog creation, actual cold native staged-bubble reconciliation and the full original scope remain unfinished.

## Verification dialog Cancel and revoked devices — 2026-10-10

Signal194 checks pass07:54:07 on APK85525888c876685f1a30d5a50d6835282704706e47083f588e2baa57fd0a12b8 (160 network/JNI,28 disconnected cold,6 online restart). Reuse the fixture's second recipient device after normal encrypted delivery: preserve its displayed number/session bytes, log out that real backend session, fetch the signed current roster and require only the surviving device. Reject confirmation of the old number with signal_device_not_ready; stored unverified status/number/ratchet remain unchanged and encrypted reopen retains rejection. This is a real server/device-key lifecycle component check; stale native dialog confirmation across revocation and physical devices still need acceptance.

Extended -PrivateVerification first clicks native Cancel on the number dialog, requires dismissal and unchanged exact number/unverified status, then reopens and confirms/removes through actual controls. Final run passes408 checks at08:06:19 on APKd1aba0443853fc06183bfa8b01faa643f949ccaa33555f17efc0499b6bb7ee48: registration30 + rounds378. The initial menu-transition failure led to a shared exact-node bounded click wait and cached predicate result, preventing double action dispatch. Earlier totals336 are corrected to364 because registration devices store numeric check counts, not check arrays. No physical-device or complete stale-dialog lifecycle claim.

Same final APK Signal194 passes08:07:43 (160 network/JNI,28 disconnected cold,6 online restart). Both suites and cleanup are terminal. Production phone-app sources are unchanged by this test/helper work, so normal122/108/159MB packages remain the previous verified outputs.

## Native device verification scenario — 2026-10-10

Final harness rebuild passes38s. Final -PrivateVerification suite passes364 checks at07:45:20 on APKddb8de43553dca74fc845dc7c3c15c54178dfd6adce45cebd05214bdf3f601a6. Both final PNGs were pulled and visually inspected: same number on both devices, full UUIDs wrap, comparison instruction and both actions fit without clipping. The earlier first-frame raster issue is superseded by the final captures; the remaining lifecycle/physical/composer limits below still apply.

Same final APK `scripts/test-signal.ps1 -Network` passes190 checks at07:48:27 (156 network/JNI,28 disconnected cold,6 online restart). An initial registrationHTTP400 was resolved for the retry by renewing only the local test invitation with the existing admin/MFA maintenance helper; credentials stayed in ignored dev.env.

Run `scripts/test-two-client-messaging.ps1 -PrivateVerification` with the dedicated two-emulator local TLS setup. This independent scenario includes native registration/key publication, programmatic private-message preparation/delivery, actual chat More options → exact device UUID/status row →60-digit number → Codes match → Remove verification on both clients. Check the visible native dialog contains the current number/full peer-device UUID and inspect encrypted status after each actual click. Existing private-history/draft/offline and disabled-composer checks remain in the scenario. Complete device changes, revoked-device confirmation, navigation/session changes while dialogs are open, physical phones and composer Send remain outside its proof.

The initial partial-label click failed because the shared accessibility helper matches exact text; the fixture now includes device UUID/status. Its first screenshot preceded dialog composition; the final harness waits for main-thread/accessibility idle before capture. The PNG stays in the test app's private files and is transferred as binary to ignored `.local` for inspection. The first completed364-check run at07:39:54 usedb4d5e7e8f71bff2396c99d48ef919466aa145d03ad25ee9d81183d5e3b07ba2b; the final evidence above usesddb8de... .

## Device-pair verification component checks — 2026-10-09

Signal network suite188 checks passes23:42:35 onb957f2506c2a56a8d34f2d3d1abf052445f9a42fd88a871c4246d548e383c68d. The codec fixture establishes real peer sessions before comparing both device-pair60-digit numbers. Confirm only a currently displayed number; check local-only/idempotent status and encrypted store reopen, then reject a wrong number/unknown device. Temporarily alter each original pinned address/identity and remove only the fixture's session; fresh target-roster processing must reject it without creating a session and an old number must not repair it. Finally restore exact original encrypted record/session and check explicit unverify leaves identities/ratchet/number unchanged. These are component checks; the native verification screen and active-roster/device-change acceptance remain unfinished.

Same-APK -PrivateAutomatic318 checks passes23:46:57, preserving real offline worker/cold reconnect/private receiving and duplicate-prevention behavior after the early pin checks. Composer remains gated.

## Native private cancellation — 2026-10-09

Run `rtk proxy pwsh -NoProfile -File scripts/test-two-client-messaging.ps1 -PrivateCancel` after the local instrumentation build. This is a separate scenario and cannot combine with private automatic/manual Retry, lost-ACK, deletion or mutation restart. It registers both emulator clients, publishes distinct keys and prepares encrypted pending rows programmatically. With actual HTTPS disconnected, open the existing message-options accessibility menu and click Cancel Sending. Require durable intent and unchanged original ciphertext/ratchet while the bubble is kept. Restore transport, force-stop/reopen and require the app worker to resolve cancellation, scrub payload and acknowledge only after native storage/UI cleanup. Another cold process restart must retain original server/negative native fences. Explicit late negative object/storage replay and actual reopened ChatActivity must remain free of the cancelled bubble.

Final report artifacts/android-two-client-private-cancellation.json passes164 recorded checks at23:30:49 on APK729e2721fb953611db29cffa1f10177520260f4f78dae154a6c15fa230d887b4. This proves unsent offline cancellation in both emulator directions; committed-send UI races, all cleanup interruption windows, cancellation retry exhaustion, physical touch/phones and native composer/device trust remain separate work. An initial final attempt terminated at pre-runner process startup ANR; a single terminal retry passed. Do not reinterpret that failed attempt as a cancellation assertion failure or claim its startup root cause fixed.

## Native private Retry scenario

Shared-entry regression: scripts/test-two-client-messaging.ps1 -PrivateKeys -ManualRetry passes325 checks at22:48:17 on the same298b1... APK, report artifacts/android-two-client-private-keys.json. Actual failed-send/cold-failed/native Retry phases and group send/edit/delete/cancellation/offline recovery pass. Actual worker terminated; a verified childless owned RTK wrapper required cleanup and outer exit1 reflects that cleanup, not failed instrumentation.

Final300-check result passes at22:41:03 on local APK SHA256298b1bbc4a848b9790659357d394f9e948de258ceb15b408c63a3e196afbcc50 (registration30 + rounds270), actual worker/host terminal exit0. Both directions include the real eight-attempt offline phase, cold failed bubble, visible native Retry click and original encrypted confirmation. The first failed-bubble screenshot was visually inspected. This proves native action wiring through accessibility, not physical touch or composer activation.

Run scripts/test-two-client-messaging.ps1 -PrivateManualRetry on the two dedicated local-TLS emulators. This mode rejects combination with PrivateAutomatic/LostAck/MutationRestart/PrivateDelete. It prepares/schedules the encrypted native pending row programmatically, removes the actual localhost transport, allows eight real app-worker attempts with natural per-record backoff, requires the failed native bubble and retains ciphertext/ratchet fingerprints. Offline instrumentation allows300s; the internal exhausted wait allows220s. Then force-stop/cold-open, require original encrypted failed state, open the existing native message-options accessibility action and click the visible localized Retry control. Confirmation must come from the app worker, without a test delivery/direct-retry API call. Require original ciphertext/ratchet, one server sender/client identity, one confirmed native bubble/no original negative and encrypted UI acknowledgement. Automatic native unsent scanning and a wrong placeholder body must not revive failure. Both directions then reuse receiving/private draft/offline/send-gate checks.

Report: artifacts/android-two-client-private-manual-retry.json. Inspect its fresh result/hash/date; command availability or successful build is not a passing result. The original accessibility action returns View's fallback after dispatch, so the visible Retry control and subsequent confirmation establish UI success. Physical touch gestures, composer activation, cancellation, device trust and full objective remain separate acceptance requirements.

## Persistent private retry checks — 2026-10-09

Same984898... APK group -PrivateKeys -Offline regression passes261 checks at22:22:24. Shared SEND/worker changes preserve actual native group send/edit/delete/cancellation and disconnected cold recovery. Actual worker exited; verified childless owned RTK wrapper was closed, producing outer exit1. Fresh passed report and final PASS output establish test outcome; physical-phone acceptance remains separate.

Same984898... APK -PrivateChat -LostAck passes288 checks at22:19:30 with161ms interruption, original ciphertext/ratchet/server identity and no duplicate native/server message. It remains a programmatic adapter delivery interruption, separate from automatic worker recovery; an interrupted worker claim lease is not proved by this test.

scripts/test-signal.ps1 -Network passes152 checks at22:13:31 on local APK SHA256984898bdfb59fce3fb8a696237a72a908832bb5ab76e23038ec833c036d8b812 (118 network/JNI,28 disconnected cold,6 online restart). Tests exercise unique claim tokens and stale callback rejection, independent durable retry metadata, bounded failures/manual revival and original ciphertext/ratchet. A real HTTP404 supplies the permanent API error. The eight-attempt test makes the encrypted fixture deadline due between attempts rather than sleeping through transport backoff. Cold restart keeps the failed record and reconstructs native SEND_ERROR from authenticated encrypted metadata. Manual retry here is a component API, not a native gesture.

-PrivateAutomatic now requires a real offline app-worker IOException with persisted attempt/status/deadline, then original retry count increasing on automatic cold reconnect and error/deadline cleanup after native UI acknowledgement. Final318-check report passes at22:16:32 on the same984898... APK. Preparation/scheduling stay programmatic and composer remains gated. Earlier314-check report belongs to the older APK; the new report proves the strengthened318-check scope.

## Automatic encrypted private recovery — 2026-10-09

Same final APK -PrivateKeys -Offline regression passes261 recorded checks at22:03:08, report artifacts/android-two-client-private-keys.json. Shared worker/SEND changes preserve native group send/edit/delete/cancellation and cold/offline recovery. The real test worker exited; a childless RTK wrapper remained and was closed only after PID/path command/parent/no-child verification. Outer exit1 records wrapper cleanup; use the fresh passed report and final suite PASS output for test outcome. No emulator data wipe was needed to restore the stopped local runtime.

Run scripts/test-two-client-messaging.ps1 -PrivateAutomatic against the dedicated local TLS service and two owned emulators. Final report artifacts/android-two-client-private-automatic.json passes314 recorded checks at19:59:56; local instrumentation APK SHA2567df6b8aa5826c69dacd1e95f356540c2e7c40e79d957ef7e0b7c10ac98917d94. The test prepares a native encrypted pending entry programmatically, proves its actual ChatActivity bubble with transport disconnected, explicitly schedules it, force-stops/reopens and awaits the app-owned worker. Recovery does not call the delivery method from the test. It requires original local/random/date/peer/conversation metadata, one matching server client identity, one confirmed bubble, removed negative native row and encrypted UI acknowledgement. Existing native private receiving/draft/offline/send-gate checks remain included. -PrivateAutomatic implies PrivateChat and rejects combination with LostAck/MutationRestart to keep recovery scopes separate.

On the same APK, scripts/test-signal.ps1 -Network passes139 checks at19:55:04 and scripts/test-two-client-messaging.ps1 -PrivateChat -LostAck passes288 at20:05:08 with299ms interruption. Explicit scheduling rejects wrong-account identity and repeated intent preserves ciphertext/ratchet. Lost-ACK recovery preserves the exact pre-interruption server UUID. These are bounded emulator/component scenarios; composer Send, durable per-record failed/manual-retry/cancellation and trust/device UX remain gated/incomplete. Background delivery with the process closed and two physical phones are not established.

## Private lost-ACK adapter recovery — 2026-10-09

Run scripts/test-two-client-messaging.ps1 -PrivateChat -LostAck. Fresh local instrumentation build53s; final report artifacts/android-two-client-private-lost-ack.json passes288 checks (registration30 + rounds258) at19:32:10, SHA2562029becfd909d8ff261fc83683d3515650e5267a2c9a38a54df1fc7e53fc6a9b. The second sender's prepared/displayed native pending row uses the original encrypted outgoing record. Existing owned local HTTPS helper holds server success; force-stop occurs210ms from request start. Reopen with the same pending metadata, compare original ciphertext and sorted Signal-session fingerprints, redeliver and require the pre-interruption server UUID, one matching sender/client live server record, one confirmed native bubble, original negative removed, idempotent delivery and journal UI ACK. Existing both-direction confirmed/offline/draft/send-gate checks remain included. Helper test: go test main.go main_test.go from scripts/lost-ack-proxy. No root Go module is added.

This is actual process interruption/native ChatActivity with programmatic adapter invocation, not native composer Send or automatic private recovery. Post-persisted-ACK/native/UI-journal crash windows and device trust remain separate. Only tests changed; prior verified b43c114 phone APKs remain current for app sources. Prior Signal137/group261 reports belong to their earlier local APK hash and are not reattributed to this build.

## Private app adapter and native pending process restart — 2026-10-09

Same final local APK scripts/test-two-client-messaging.ps1 -PrivateKeys -Offline passes261 checks (registration30 + rounds231) at19:18:43 local. Actual group composer send/edit/delete cancellation/confirmation, receiving changes, cold deleted absence and offline queue/automatic reconnect/confirmed cold reopen remain valid after the shared history change.

Run scripts/test-two-client-messaging.ps1 -PrivateChat on the final local test APK. Final report19:11:36 passes276 checks (registration30 + rounds246), SHA256503f8ad5d632f8716472b0843853ba26f7d2bc020a46475e4ee9d0904ce4a676. Both sender directions prepare a private encrypted journal/native placeholder row through the app adapter, open actual ChatActivity, force-stop, remove the local HTTPS reverse mapping, reopen offline and verify original client/local/random/date and visible pending text. Restore transport, deliver through the adapter, require one confirmed bubble and no old negative row, repeat delivery with the same server ID, and verify encrypted UI work marked done. Existing confirmed/offline/draft/native send-gate checks remain. Host report states REST conversation setup and programmatic adapter entry; native composer Send and automatic private retry/cancellation/trust remain gated.

Run scripts/test-signal.ps1 -Network: final137 checks pass at19:14:09 on the same APK (106 +25 +6; three distinct PIDs15850/15984/16107). Date tampering, premature ACK and wrong account/local/dialog/random/server-ID ACK are rejected. Exact repeated ACK removes journal work without changing original date/server ID or advancing the ratchet. Received/pending volatile native display and disconnected original-row component checks remain covered.

Native history restores random IDs for negative private user rows, retaining existing pending group and encrypted-dialog behavior. New pending-reopen phase uses the existing bounded offline activity startup. Saved online pending-startup trace proved startActivitySync waiting while main draws; pending delivery uses the same visible-activity startup rather than unbounded global-idle waiting. The actual bubble assertions remain unchanged; every chat phase has the existing50s diagnostic trace. Physical ARM installation is separate; no phone is connected. Latest normal universal APK158670010 bytes, Android8+, ARM32/64, Debug/public TLS/no local CA.

## Encrypted pending row component — 2026-10-09

Final suite132 checks at18:03:07 local, APK6d2ee49dcd55c80c992b05003796f8a2ab99b436a665c52ded425f66552e94a9. Added received/pending native-owner replacement with empty body and original-owner restoration. Old volatile text must clear even though native applyNewText ignores empty strings. Pending replacement is exercised again from the cold native row offline. Null-owner clearing branch is not separately exercised. Native composer delivery remains gated.

scripts/test-signal.ps1 -Network now covers126 checks at17:49:10 local on APK49455be4b8a5dbe9ac0e228e457f9abfc2df1476cafb52313499b68de6d4108b. Prepare the native identity using an actual IdMap peer, persist a real placeholder negative native row, force-stop/remove transport, read its actual serialized row/random/send state and require constructor-restored volatile text. Scan native blobs/serialization for fixture plaintext; mutate random and vault user and require clearing. Preserve account3 borrowed-slot guard/cleanup; delete only the dedicated native fixture row after cold read. No account reset or security bypass. Actual composer creation/scheduling/retry/cancellation/ACK reconciliation and foreground pending chat remain unfinished.

## Private send safety gate — 2026-10-09

Latest -PrivateChat passes226 recorded checks at17:31:20 on local APKfd3046e6098665f97eddc606ef4afd105acfa501a09b8d17d8f3ec7b97945bd8. Check actual disabled/dimmed Send, force composer submission, invoke shared native send/forward/screenshot entries and query native messages_v2: no negative private row or plaintext pending bubble; composer draft stays intact and cold-restores offline. This gate prevents the earlier bridge-only rejection from clearing/persisting private text. It does not enable encrypted private delivery; remaining media preparation/UI and legacy storage require separate acceptance.

## Private draft checkpoint — 2026-10-09

Run scripts/test-two-client-messaging.ps1 -PrivateChat against the existing local HTTPS/DB and two dedicated emulator fixtures. Latest run passes202 recorded checks at17:13:17, APK416d289422d42f49cac5889ef9db59070720e1e53a10c49e3b956a3ee40784e5. Besides actual receiving bubbles/native SQL privacy, it enters a private draft into the actual composer, calls native saveDraft, checks encrypted preferences and restores the same draft after force-stop with transport disconnected. Reject corrupted/swapped/legacy records and peer UUID reuse; preserve group serialization. This is programmatic input, not physical UI acceptance or actual reply-save coverage. Unknown legacy drafts are retained but unavailable; migration/recovery feedback remains required. Phone compatibility report remains unresolved pending model/OS/file; universal includes ARM64/ARMv7, Android8+.

## Native-send identity component — 2026-10-09

scripts/test-signal.ps1 -Network also prepares deterministic user:random_id native identities through SignalMessages.prepareNative. Check transaction rollback, changed row rejection, server ACK retention, removal after deletion and metadata-only account-scoped recovery. A real disconnected cold app restart must preserve original local/dialog/random/client IDs and exact ciphertext without ratchet advance. The borrowed inactive native account3 must remain empty on disk after cleanup; refuse an already active slot, never clear it to make the test pass. This component does not create or send a native composer row. Before wiring it, protect native pending-message/draft persistence from plaintext and bind the mapped native peer/account to the authenticated owner. Private Send stays gated until UI recovery/reconciliation is verified.

## Private deletion replay — 2026-10-09

Run scripts/test-two-client-messaging.ps1 -PrivateDelete after a local x86 instrumentation build. Includes the full -PrivateChat receiving/cache scenario, deletes the second fixture message through authenticated REST, and cold-opens both actual private chats online and offline. Require stream-learned persistent native tombstones and a visible known-live first bubble before asserting the deleted message is absent. Controlled stale plaintext objects, new/edit updates, real notification dispatch, dialog previews and MessagesStorage.putMessages must not restore it. A newer object from another dialog must never replace this dialog's preview; absent same-dialog fallback clears the preview. Query actual messages_v2 and the existing dialogs row after replay. No fixture directly seeds the tombstone. Native private composer Send/Delete controls remain gated and untested; physical phones and deletion not yet learned before disconnect remain separate requirements.

## Two native private chats — 2026-10-09

Run scripts/test-two-client-messaging.ps1 -PrivateChat after a local x86 instrumentation build. Reuses native registration/key-publication setup, then sends encrypted text through SignalMessages as a fixture in both directions. Actual private ChatActivity cells and native database rows are checked on both clients, including force-stop and offline reopen with each18443 reverse removed. Restore mappings and remove private fixtures in finally. Screenshots remain ignored in .local for visual verification. Native composer sending is not exercised or enabled by this test.

Shared bounded SDK helper explicitly uses UTF-8 input/output; scripts/test-android-test-tools.ps1 verifies Persian/emoji round-trip, preserved exit code and timeout termination of only the owned helper. Offline activity readiness handles existing test permission prompts before requiring real app window focus. For an exhausted local invite, scripts/renew-local-test-invite.py uses ignored dev.env bootstrap admin credentials, localhost TLS/private CA and MFA to create a fresh normal invite and logs out. No credentials/code are printed, no redirect/proxy inheritance, no production endpoint or database quota reset. Keep .local and environment files out of Git.

## Context-bound private display component — 2026-10-08

scripts/test-signal.ps1 -Network additionally validates automatic signed sender directory resolution without one-time prekey consumption, address/version rejection, and offline cached duplicate receive. The display reader requires authenticated peer/conversation/sender/revision/ordinal and respects deletion/expiry fences. A UI-thread synthetic MessageObject check uses applyNewText and serializes the underlying TL message to prove the placeholder is retained. This does not enter a real private ChatActivity, inspect MessagesStorage blobs or prove physical-device delivery; those remain required when wiring the renderer.

## Authenticated encrypted history after offline process restart — 2026-10-08

scripts/test-signal.ps1 -Network now runs signal-network, signal-network-offline-restart, then signal-network-restart. The host removes the scoped emulator18443 reverse before force-stopping/relaunching the app; the offline phase requires an actual IOException from authenticated HTTPS before testing cache access. Restore the same reverse before online retry and cleanup, including on failure. Three distinct actual app process IDs are recorded.

Latest run passes76 checks (59 network+11 offline+6 online restart), artifacts/android-signal-network.json at22:50:26; local APK SHA256c52cd64f115a6c1883bf3cf2c9c07777c9932eed44737eb60654dc8d60afaaa6. Both encrypted history caches open offline, pending ciphertext/ratchets stay unchanged, duplicate receive requires no directory, changed account/session and reused old-session device proof are rejected, mismatched encrypted device binding is not replaced, missing store creates no identity, and a valid other-account login can replace corrupt unrelated token ciphertext. Includes a real server-device UUID differing from the requested local UUID. These are service/cache tests; native private rendering/sending remains gated.

## Compact Debug packaging and device-key readiness — 2026-10-08

The app module pins the same NDK27.2.12479018 as the native library module, allowing AGP to strip debug symbols. Exclude only libsignal_jni_testing.so from JNI packaging; retain libsignal_jni.so and libtmessages.49.so. The build wrapper recreates only the selected variant's APK output and incremental packaging cache, with validated paths inside android/app/build. This prevents deleted ZIP content from leaving hundreds of MB of unused archive space. Normal APK verification rejects test-only JNI and single-architecture files above250,000,000 bytes.

Run scripts/build-android.ps1 -Abis arm64-v8a,armeabi-v7a, then scripts/verify-apks.ps1. Fresh normal Debug artifacts:ARM64 122,428,217 bytes, ARMv7 108,141,523 bytes, universal 158,653,634 bytes. No local CA; no release-signing claim. After the same fresh local x86 build, scripts/test-signal.ps1 -Network passes64 checks (58 network+6 real process restart), APK SHA256d9265fc63d0ec059b43a9a1cd6abe88270496764b1ddaeef7e10bb71a01c8349. This verifies actual HTTPS/JNI encryption and decryption after stripping and removal of test JNI; physical ARM testing remains separate.

scripts/test-two-client-messaging.ps1 -PrivateKeys -Offline adds automatic native-dialog key publication to existing independent registration and foreground/offline messaging. Each authenticated installation must publish its own distinct device/address, then preserve that publication on cold app entry. The phase waits for automatic preparation before calling the idempotent helper; an explicit helper call cannot conceal a missing native trigger. Private send UI remains gated. These are emulator checks, not proof of full private chat or physical-device completion.

## Native mutations interrupted before acknowledgement — 2026-10-08

Run `./scripts/test-two-client-messaging.ps1 -MutationRestart` after local x86 instrumentation build. It includes -LostAck/-Offline, then sends/caches a fresh message on both native clients, captures owned/live server identity, presses native Edit/Done and shared Delete cancel/confirm, holds successful upstream acknowledgements and force-stops the actor before10s. Both cold histories verify edited identity/revision/uniqueness and repeatedly verify deletion without resurrection. Existing fault helper accepts -operation send/edit/delete and selected -message UUID for mutations; existing local CA checks remain active. No new dependency or production policy change.

Final report artifacts/android-two-client-mutation-restart.json passes412 recorded checks, APK SHA256451305f23d695e7013348865ca48812f536579ab42e6574a132c6c7e0af51c8b, build1m1s. Stop durations:send693ms/edit720ms/delete245ms, before real20s read timeout. Intentional interruption has its own status and captured native check snapshot; successful restart phases still require normal passed instrumentation. Native chat entry is programmatic and group setup is REST fixture. Physical devices and arbitrary pre-commit/storage/account-switch/offline mutation acceptance remain separate; do not claim complete messaging or whole-goal readiness from this scenario.

## Verified interruption before read timeout — 2026-10-08

The refined `./scripts/test-two-client-messaging.ps1 -LostAck` now passes285 checks after a59s local instrumentation build. Fresh report artifacts/android-two-client-lost-ack.json; local APK SHA256376c1654a8a9fc809ba8f66cd050181d05e128fccceeedd305e9b89c7158bde7. Interrupted phase records original queue SENDING and interruption_ms=462, below its10s limit and the app's20s read timeout. After a real successful response is held and the app is force-stopped, original queue/client/server identities reconcile once; recipient bubble and confirmed cold reopen pass. This supersedes the earlier pending/refinement paragraphs below. Interruption has explicit interrupted_as_requested status, never an ordinary successful instrumentation completion. Physical devices and broader mutation/account/background/private/media acceptance remain separate.

## Lost acknowledgement fault test — 2026-10-08

After local x86 instrumentation build, run `./scripts/test-two-client-messaging.ps1 -LostAck`. Requires installed Go to compile the stdlib helper in scripts/lost-ack-proxy/main.go. The helper binds only127.0.0.1:18444, forwards to existing localhost18443 with the local public CA and unchanged certificate checks, and holds only the first successful group-send response. Only the peer's18443 reverse is temporarily mapped to18444; cleanup restores it and stops the owned helper. Private state contains server identity/status/timing, never message bodies or credentials. `go test scripts/lost-ack-proxy/main.go scripts/lost-ack-proxy/main_test.go` tests fault selection, upstream success proof, cancellation and one-shot release.

First complete run passes284 recorded checks, artifacts/android-two-client-lost-ack.json, local APK SHA256555f5f537790af8bd69f9cdd41639b5c841d82997d6898ec8e34a15d302c8cfd. Native Send, real server commit, intentional app interruption, original queue/server-ID recovery, exactly-one native/server message and confirmed cold reopen pass. Explicit interruption has its own result status. That run's proof/log interval was about20s; it does not prove a stop before read timeout. Current refinement adds SENDING proof and a10s completed-interruption limit using request_started_ms; pending helper uses the immutable user captured before Send. This latest variant is not built/run because automatic approval review hit the account usage limit. Build/rerun it before relying on strict in-flight acceptance. Physical devices, broader mutation races and full objective remain open.

## Verified native Retry after failed cold reopen — 2026-10-08

`./scripts/test-two-client-messaging.ps1 -ManualRetry` now passes301 recorded checks (registration30 + messaging/lifecycle271), saved in artifacts/android-two-client-manual-retry.json. Local APK SHA2564bfd095e15efab8bbbf5d741d2a45df05aa3fe8550b24f64596e6494485fc025. The first recovered run exposed a real identity regression: normal-group history discarded the persisted native random ID, so Retry delivered under a new queue client ID. The pinned-source overlay restores that ID for negative group messages. Both cold pending/failed reopens now verify the native ID derives the original account-bound client ID; native Retry confirms the original row SENT, matching server identity, one native/server message and cold confirmed reopen. Physical phones, lost acknowledgements/mid-mutation interruption and broader E2EE/background/media/release scope remain separate requirements.

## Alternate runtime and bounded SDK probes — 2026-10-08

The peer's persistent unresponsive shell/console was recovered by stopping only its verified host process and preserving guest data. A subsequent Canary37.3.2 swangle cold launch really crashed with0xC0000005 (monitored launcher plus Windows Application Event1000, unknown module). Do not infer this root cause from earlier timeout-only observations.

The official Stable37.2.12 Windows archive is verified and extracted separately in ignored .tools/android-emulator-37.2.12. Launch the existing peer with `./scripts/start-emulator.ps1 -Serial emulator-5562 -GpuMode host -ColdBoot -Monitor -EmulatorPath ./.tools/android-emulator-37.2.12/emulator/emulator.exe`. The selected runtime uses the existing SDK images and isolated AVD data; it does not replace the primary runtime. ColdBoot disables snapshot loading and does not wipe data. Starter uses shared bounded SDK probes and rejects unknown emulator identities. A boot observation timeout leaves the owned emulator running; recheck that process before recovery. `./scripts/test-android-test-tools.ps1` verifies stdin, exit propagation and bounded helper termination without a device. The successful301-check ManualRetry report above establishes this fixture only.

## Real failed-send and native Retry scenario — 2026-10-08

`scripts/test-two-client-messaging.ps1 -ManualRetry` includes the previous offline lifecycle, then holds the dedicated proxy offline until real eight-attempt retry exhaustion. Native Send must become an error bubble, journal must be FAILED with8 attempts, cold offline reopen must preserve the same failed bubble/client ID. Restore proxy, start receiving client, open native failed-message menu/Retry and require original queue row SENT plus one native/server message, matching server identity and confirmed cold reopen. Failed-send runner gets300s host bound/200s native exhaustion bound; other phases retain normal bounds. No injected failure or shorter production retry settings.

Historical failed attempt: build51s passed, but peer ADB/control became unresponsive during earlier deletion, before manual Retry. Bounded helper regressions (real stdin and real timeout) passed; no success report existed then. This is superseded by the recovery and301-check result above; do not reuse those older logs as evidence for the corrected scenario.

## Offline queued send and recovery — 2026-10-08

Use `scripts/test-two-client-messaging.ps1 -Offline` with the dedicated matching local TLS proxy and both known clients. Runs the complete send/edit/delete prerequisites before a network-step handshake stops only the validated proxy, closing all test TLS connections. Actual Send creates visible pending bubble and one durable queue entry. Offline cold app entry skips online metadata setup and verifies cached native pending bubble/same client ID. Restore proxy, await recipient readiness, start sender recovery and check visible confirmed bubbles, original queue SENT/server ID and exactly-one native/server message; cold confirmed sender reopen and both deleted-message histories repeat these checks. Proxy is restored in cleanup; no production service stopped.

Pass236 recorded checks (30 registration +206 messaging/lifecycle), local APK SHA256869e844123020e0bc9821b30531d60430ee6eb5418f816169904eec5788f9e45; artifacts/android-two-client-offline.json. Offline launch uses bounded real window focus/fragment readiness rather than startActivitySync/global idle, which hung in the observed trace while main processed frames. Test-only50s final build. Counts/stack-only private diagnostics capture long offline-launch waits; timeout preserves phase logs. REST fixture/programmatic native chat entry and accessibility/View clicks remain explicit. Manual retry, lost acknowledgement, interrupted mutation, physical phones/touch and broader races/background/media/E2EE/release acceptance remain required.

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
