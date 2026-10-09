# Decisions

## Bind native private drafts to their authenticated owner and peer — 2026-10-09

Reuse native draft preferences and Android Keystore AES-GCM instead of adding a second draft database. Encrypt the original native serialization at all private draft/reply writes; bind account, authenticated UUID/native owner, actual peer UUID and complete record key as AAD. Require current owner/peer mapping before and after cryptography. Keep group serialization unchanged. Reject legacy private hex because its owner cannot be authenticated; retain it pending explicit migration/recovery UX rather than assigning it to a later login. Actual reply paths, account-switch UI and legacy handling still require acceptance.

## Recheck mutable native row provenance — 2026-10-08

Validate the exact native object/row, message ID/dialog/peer/sender/date/edit and stored placeholder before assigning private text and while retaining it. Native owner/session checks alone cannot detect a changed vault user that reuses the session string; also require the originally authenticated user. Test mutable sender/date and same-session user changes, metadata deletion, populated v3 migration, and actual native row persistence. Keep private text in volatile formatting; native messageOwner remains the placeholder. Complete ChatActivity/device/lifecycle acceptance remains required.

## Native encrypted text hydration — 2026-10-08

Use the existing IO executor and immutable encrypted-cache display API from the pinned MessageObject constructor. Persist only routing/revision/date/edit/encrypted/deleted metadata in IdMap v4; old v3 rows default to nonencrypted until a fresh observation, preserving fail-closed provenance. Check the exact native object, owner/session, current metadata and native deletion snapshot before applying volatile text. Update visible cells through a scoped updateInterfaces mask rather than replacing objects by message ID; stale objects cannot replace newer edits. A weak-reference timer clears volatile text on invalidated provenance/account/session or expiry. Private sending remains gated. Constructor hydration is tested; actual ChatActivity/storage/lifecycle acceptance still required.

## Private receive metadata and volatile text — 2026-10-08

Use authorized signed sender directory metadata for incoming decryption; reading a message must not consume sender one-time prekeys. Reuse immutable cached bindings offline and reject changed identity/address. Before handing encrypted-cache text to native formatting, require matching peer/conversation/sender/revision/ordinal and enforce deletion/expiry. Keep TL messageOwner as the placeholder; MessageObject.applyNewText can hold volatile layout text without changing serialized message bytes. The component check does not replace actual ChatActivity/native-storage lifecycle verification. Native private sending remains gated until the remaining identity/device/outbox workflow is connected and verified.

## 2026-10-04 — Signal foundation
- Replace the unused initial signal-v1 transport with signal-v2 before any private UI release. Bind the producer and each destination to user/device/immutable Signal address/current key version. Encrypt separately for every active recipient device and the producer's other devices; exclude the producing device and retain its own plaintext only in encrypted local storage. Never silently omit unprepared devices or collapse devices from different accounts that share a physical UUID.
- Lock participant users before sessions, then key records, through private message/event commit. Recheck lifecycle changes after lock acquisition. Preserve exact committed idempotent retries after roster/key changes; apply current-roster checks only to new ciphertext sends/edits. Private edits record their actual producing device. The complete production client still must authenticate inner conversation/client/revision metadata and persist ratchets/ciphertext/cache atomically before network access.
- Bind key networking to the original authenticated session as well as account; obtain device identity from the authenticated self profile rather than assuming a copied vault's local device preference is authoritative. Refresh must preserve both bindings before saving replacement tokens. Validate actual identity signatures and strict claimed-bundle metadata before allowing SessionBuilder to use a bundle.
- Persist publication acknowledgement only after validating its device/version/address. If local persistence fails after server commit, replay the exact durable initial publication to recover the same generation address. Keep automatic scheduling/refill/rotation and full UI lifecycle acceptance open.
- Persist initial private keys and the exact public publication atomically before HTTP. Retry the identical publication after a lost response, including consumed EC IDs; never regenerate private keys to repair a missing publication record. Automatic refill/rotation and acknowledgement reconciliation require separate verified lifecycle work.
- Pin official libsignal0.103.0, official Maven artifacts and upstream license; use JDK21 and Java17 Android source/target. Keep the full corresponding-source/license audit as a release gate.
- Use a server-generated immutable UUID Signal address for each device-key generation with protocol device number1. Keep account/device session identity separate from the cryptographic address.
- Public-key publication requires an explicit expected version. Identity/registration are immutable; signed/Kyber rotations require increasing IDs. Identical accepted publications are retryable and claimed EC IDs never return to the available pool.
- The server validates wire structure; the receiving Signal client verifies signed EC/Kyber signatures. Claims atomically consume one EC key and use the signed last-resort Kyber key when the EC pool is empty.
- Store private state under Android no-backup storage with Keystore AES-GCM and account/record-bound authenticated data. Ratchet writes and persisted ciphertext/cache changes share one SQLite transaction. Missing keys, changed identities and corrupted records fail closed.
- Keep private messaging disabled until publication, trust verification, complete device lifecycle, durable network retry, encrypted fanout and the original UI are connected and verified. Foundation tests are not product completion.

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

## 2026-10-02 — atomic creation-time group auto-delete
- Accept the native createChat TTL flag and persist group disappearing_seconds in the initial conversation transaction with membership and recipient events. Never create a group and then patch its TTL in a separate request.
- Validate the same0-to31536000-second bound used by existing settings. Initial private-conversation creation still requires zero; its existing settings API remains unchanged.
- Map persisted TTL into native dialog fields/serialization flags and the creator's peer-history-TTL update. The first send derives its expiry directly from the committed conversation setting.

## 2026-10-02 — full group information
- Read conversation metadata and member roles/join dates in one authorized PostgreSQL statement. Targeted lookup must work for groups outside the bounded500-item list; removed members and outsiders receive the same404.
- Map that snapshot to the original messages.getFullChat response with native owner/admin/member participant types, actual membership dates, TTL flags and group creation date. Unknown inviter metadata stays unset.
- Use targeted repository lookup for incoming-event conversation metadata as well. Native role reads/cache are separate from unfinished member-management mutations and permission controls.

## 2026-10-02 — native group rename and partial settings
- Change only supplied conversation settings under the existing row lock, return effective values into recipient events and preserve omitted fields. A title-only rename must not reset auto-delete or overwrite a concurrent TTL change.
- Route native editChatTitle through the repository and return a standard updateChat metadata notification alongside the authoritative chat. The original MessagesController processes cache/UI refreshes.
- Verify queued native cache writes on the storage queue before asserting rename persistence; network callbacks alone do not prove cache completion.

## 2026-10-02 — native history TTL updates
- Route messages.setHistoryTTL through a TTL-only repository PATCH, preserving the group title. Return the effective authoritative period through the standard peer-history-TTL update, including zero to disable.
- Remove Telegram's optimistic setDialogHistoryTTL cache mutation in the pinned-source preparation step. Only accepted server updates may alter cached full-group settings; rejected requests must not create a local success state.
- Emulator checks exercise the original controller, enable/disable/re-enable, serialized full-group cache, new-message expiry and process restart. All94 checks pass across first/offline/restart. Compare participant dates to the server snapshot rather than the device clock; wait for the UI handler to schedule cache writes before storage verification.

## 2026-10-02 — foreground group metadata events
- Resolve conversation.updated against the current authorized group snapshot rather than trusting stale event-body settings. Use standard native chat and history-TTL updates, preserving the actual creation date and serializable role/name fields.
- Bind the snapshot read to the repository account lock and check repository/native identity before native application. Wait for the stage/UI/storage queues and verify the persisted chat title before marking the event processed.
- The98-check emulator suite verifies other-member repository changes reaching the receiving Android cache over WebSocket, TTL zero flags and persistence through process restart. Two physical-device acceptance, removal authorization races and complete UI transaction recovery remain separate work.

## 2026-10-02 — native membership events
- Resolve member.updated/member.removed against the authorized current group snapshot and reuse one participant mapper for full-chat reads and realtime updates.
- Update loaded full-group participants before standard TTL processing can serialize old members. Membership updates use the standard participant notification without requesting the same full snapshot again.
- Telegram creator participant serialization has no join-date field. Verify owner cache date zero and exact server dates for member/admin classes; do not invent a custom wire format.
- Current-account removal/stale authorization events, native mutation UI/permissions, absent full-cache recovery and account-switch races remain unfinished.

## 2026-10-02 — invitations preserve existing roles
- Native Telegram promotion can add a user before editing their admin role. Add invitations must not reuse an upsert that changes an existing role to member.
- The member endpoint accepts preserve_existing=true. Authorization and the conversation row lock apply before the existing-member no-op; explicit role updates retain existing behavior. This prevents a concurrent invitation from undoing promotion.
- Fifteen API tests and go vet pass, including five actual concurrent invite/promotion pairs, owner preservation, member denial and admin invitation of a new member. Native mutation wiring and running Core replacement remain pending.

## 2026-10-02 — native member-management requests
- Map addChatUser to preserve-existing invitations and invitedUsers updates, editChatAdmin to explicit role changes and Bool, and non-revoking deleteChatUser to removal plus authoritative participants. Resolve InputUserSelf explicitly; reject unmapped input-user variants.
- Keep unsupported nonzero forwarding, history revocation and current-account removal unavailable before server mutation. The pinned deleteParticipantFromChat always requests revoke_history; its removal flow remains unfinished. The official API defines that flag as removing the specified user's history in this chat: https://core.telegram.org/method/messages.deleteChatUser .
- Rebuild local Core with the previously tested invitation flag before native tests. All109 checks pass for native add/admin role operations, preserved roles, owner denial, non-revoking transport removal and prior offline/restart regressions. All three normal ARM Debug APKs built and passed signature/package/API/ABI/manifest verification.

## 2026-10-02 — observed message revisions
- Expose server revision/conversation/edited timestamp in repository models and use the standard native edited flag/date. Keep incomplete acknowledgement revisions unknown rather than assuming one.
- Schema3 IdMap stores transactional, monotonic sender/group/revision/body-hash observations. Preserve populated older IDs; require a fresh observation for legacy edit eligibility. Same-revision computed expiry tombstones invalidate eligibility without allowing resurrection.
- Matching an observed text hash is not proof of the exact version originally shown by the UI, especially equal-text ABA edits. Composer entry must capture an immutable exact viewed revision; views without it must refresh or fail closed. The native edit transport/controller is still unfinished.

## 2026-10-02 — immutable native text editing
- Attach immutable server identity/revision provenance to mapped in-memory messages using a subclass with unchanged standard TL serialization. Decoding native cached messages intentionally does not fabricate a revision.
- Capture that provenance at the original composer entry. Save creates a separate request carrying the frozen account, sender, group, UUID, ordinal and revision; newer equal-text observations cannot replace it.
- Submit the exact revision through the existing server compare-and-swap mutation. Unknown cached revisions require reload, and conflicts show an explicit Persian/English alert. Never fetch a newer revision to make a stale edit succeed.
- Feed accepted authoritative updates through the native update-array processor without treating stable message ordinals as Telegram PTS. Validate account identity again before native application; deleted authoritative states produce tombstone updates.
- This connects plain group-text editing only. Full interactive composer/reload acceptance, rich entities, media edits and broader account-switch races remain unfinished.

## 2026-10-02 — persisted native edit provenance
- Store the exact mapped revision, UUID, group, sender, ordinal, timestamps and text SHA-256 in the existing app-private messages_v2.custom_params blob. Extend only its local Params_v1 flag14/string; standard Telegram constructors and message wire serialization remain unchanged.
- Restore that local snapshot when reading cached messages. Composer capture validates identity, timestamps and body hash and freezes the persisted revision even if newer observations exist. Do not infer cached revisions from the newest matching text.
- Old blobs without the field remain readable and explicitly clear any stale provenance when decoded into an existing object. Unknown, malformed or content-mismatched snapshots require refresh; no database migration or fabricated revision is needed.
- The native custom-parameter copier does not transfer a snapshot from an older message to a replacement; incoming authoritative snapshots retain their own version.
- Native edit storage and replaceMessageIfExists merge old local UI attributes while preserving the incoming provenance, or remove it when the incoming version is unknown. Never copy an old basis alongside newly received content. The132-check emulator suite verifies latest persisted revision and a successful original-helper edit after process restart.

## 2026-10-02 — authorized atomic group deletion
- Delete1–100 distinct message UUIDs in one conversation/message-lock transaction. Check all group/sender permissions before changing any row; tombstones, content/media clearing, revision increments and recipient events commit together. Authorized retries are no-ops for already deleted/expired messages.
- Acquire the group lock before reading membership in a fresh statement. A request waiting behind demotion/removal must use the newly committed role, not a pre-lock join snapshot. Real PostgreSQL lock-wait tests cover both cases.
- Intercept the original MessagesController remote deletion before optimistic native removal. Bind requests to the originating account/group; apply the original cache-only removal only after the entire server request succeeds. Permission errors preserve every selected native row.
- Native history/dialog feeds use server-side live_only filtering before LIMIT, preserving stable ordinal cursors across deleted rows. Resolve replayed message events against current deletion state; a tombstone can never map to a live blank message.
- Delete-for-me, private/scheduled/channel deletion, legacy pending-task replay, durable offline deletion intent and full screen/account-switch acceptance remain unfinished. Unsupported modes fail before local deletion; never reinterpret delete-for-me as delete-for-everyone.

## 2026-10-03 — native blocking and owned block lists
- Commit block/unblock and owner-only device events atomically. Repeat operations preserve the actual creation date and emit no duplicate events; self and unsupported story variants fail before mutation.
- Paginate owned public user metadata with a consistent total, including an empty page beyond the end. Preserve legacy unknown dates rather than inventing a migration timestamp; disabled targets remain manageable when already blocked.
- Update the original MessagesController block set, known count and loaded full-profile cache only after server confirmation. Preserve cached rows on list failure and offer actual Retry in the original PrivacyUsersActivity.
- Read authoritative current state for incoming block events and verify loaded native full-profile persistence before considering the event processed. Complete offline block-list persistence and adversarial late callback/paging races remain acceptance work.
- Capture specialized request identity and primitive fields at the public ConnectionsManager send entry, before stage-queue deferral. A caller must not mutate the queued input peer, flags or native account into a different operation. Broader generic request immutability remains unfinished.

## 2026-10-03 — static native profile pictures
- Keep the original ImageUpdater picker/crop/preview and SettingsActivity response flow. Upload a static JPEG through the repository, then bind its owned media UUID through the original photos.uploadProfilePhoto request; uploading alone must not change the profile.
- Validate JPEG/PNG dimensions before full decoding on the server and discard corrupt or oversized images without a media row. Expose actual current-photo metadata; do not invent sizes or creation dates.
- Share the existing IdMap in a dedicated application-wide slot(-1) for avatar UUIDs. Telegram's image cache is shared across native accounts, so per-account integer sequences would make unrelated photos collide. Native locations use the normal negative photo ID and a/c suffixes; both variants currently serve the same validated original image.
- Adapt original upload.getFile requests to authenticated, bounded HTTPS ranges with refresh and explicit EOF. Release unused native byte buffers when cancelled or rejected by the account guard.
- Use native datacenter identifier1 for the single VYRN origin, preserving it in Photo/UserProfilePhoto and UserConfig. The original downloader rejects0 before dispatch; identifier1 is local compatibility routing and never selects a Telegram endpoint.
- Preserve the picker account/origin, suppress completion after cancellation, and leave abandoned uploads unbound for existing orphan collection. Static profile pictures do not enable video, bot, fallback or contact-photo variants.
- Delete the observed current media UUID under the owner row lock. A newer replacement returns409; repeated removal of an already empty profile is a no-op. Native caches change after acceptance and UserConfig persistence precedes success.
- Current-photo listing is connected; past-photo history, profile realtime propagation, thumbnail derivatives, full gallery/camera gestures and adversarial late callbacks/storage failures remain unfinished.

## 2026-10-03 — foreground profile synchronization
- Commit actual profile changes and owner/current conversation-peer events together. Transmit only the changed public user identity and reload the authorized current profile; avoid global broadcast, duplicate recipients and events for unchanged or rejected mutations.
- Reuse the original full-profile request adapter and apply accepted fields through MessagesController's existing user/full-user caches, storage and notifications. Preserve the current user object and unchanged photo object so realtime reloads cannot replace an editor's active references. Save self UserConfig synchronously, roll back user fields if that save fails, and verify SQLite before considering the event processed.
- Carry the immutable journal owner's identity into the event handler and recheck native/vault identity. Keep bounded retry/dead-letter behavior on application failures; do not acknowledge failed native persistence as processed.
- Match the native download response resource contract: free unused byte buffers after callbacks while respecting disableFree when FileLoader retains a delayed chunk. Preserve cancellation and identity-rejection cleanup.
- Discovery-only profile subscriptions, adversarial concurrent edits/account-switch/lifecycle/disk failures, background execution and complete physical-device/UI acceptance remain unfinished.
## Private text transport transaction — 2026-10-04

For new private text, acquire and verify both complete rosters before claims. Require the producing device's published address/version/identity/registration to match local keys; include all ready sender-other and recipient devices, keeping per-user physical UUID identity separate from globally unique Signal addresses. Use signed metadata for existing sessions and claim one-time bundles only for missing sessions. Persisted retries bypass roster acquisition and preserve original ciphertext; do not silently rebuild after a lifecycle conflict. Final backend send locking validates snapshot freshness.

Learn disappearing-message deadlines from authorized server DTOs before exposing the producing device's own cache. Persist the earliest learned deadline across duplicate/revised DTOs, enforce it locally even offline, purge all decrypted revisions/own outgoing text atomically and retain permanent replay/send fences. Use the existing encrypted record table for an idempotent expiry sweep; connect that sweep to the native lifecycle worker before release. Keep unknown-deadline reconciliation, clock-change handling and large-history costs explicit acceptance gaps.

Use the existing official libsignal and encrypted SignalStore rather than a second crypto or outbox database. Commit all destination sessions, peer bindings, exact HTTP body and own plaintext cache in one transaction before sending. Authenticate conversation/client/revision, sorted participants, sender/recipient tuples and sorted destination metadata inside each ciphertext. Persist decrypted history with ratchet/prekey mutations in one receive transaction; rollback malformed authenticated context. Retain immutable identity/address bindings and reject automatic identity replacement. Private UI stays disabled until scheduling, verification, lifecycle and complete native acceptance are finished. The text-only codec is an implementation stage, not a reduction of the original MVP scope.

## Automatic local-device key publication — 2026-10-08

Native dialog loading starts one asynchronous publication task on the existing IO executor. Bind its result to the original native account and authenticated session, rechecking both after completion. Failed preparation can retry on the next dialog load without failing normal group history. Persist private material before HTTP; replay the same initial public bundle only when its durable acknowledgement is absent. With an acknowledgement present, validate the authorized own-device signed bundle against the encrypted local identity, registration, address and version; never replace a mismatched generation automatically. Process readiness describes only this device's publication, not current peer trust or complete-roster readiness. Future private sends still require the existing complete-roster validation. Private send UI remains gated.

## Offline Signal cache opening — 2026-10-08

Cache opening must not require GET/v1/me. The online key constructor authenticates the original session's actual server device UUID, stores that UUID and source session inside the encrypted TokenVault, then persists the matching user/device binding inside SignalStore. Preserve this proof through refresh only for the same user/session; do not infer the actual server device from the local requested device UUID. The existing cross-account/device-UUID fixture proves those can differ.

SignalDeviceKeys.openLocal requires matching source-session proof, an existing SignalStore with complete identity records, a matching encrypted authenticated-device binding and prior publication acknowledgement. It never creates a missing database or regenerates identity material. Existing SignalMessages session guards, atomic cache/ratchet mutations and expiry/tombstone fences still apply. Old installations without proof must authenticate online once; offline access cannot establish remote session-revocation status. This enables the cache primitive for later safe native rendering, not completed private UI or a plaintext native-cache bypass.
