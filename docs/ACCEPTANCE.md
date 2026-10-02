# Acceptance matrix

Updated 2026-10-02. A route existing or a build compiling does **not** establish end-to-end completion.

| Requirement | Current evidence | Remaining work |
|---|---|---|
| Local repository and memory | Root, main/develop/work branch, confirmed config and ignored secrets established | Keep memory current; no remote yet |
| Telegram source pin | Exact commit and submodules downloaded | Reproducible source export and full branding audit |
| Original Telegram UI | Original UI compiled through VyrnApp; login and adapter overlays supplied | Finish all unsupported UI paths and settings; device verification |
| Debug APK | ARM64/ARMv7/Universal built; signatures/ABIs/manifest verified; x86_64 emulator installed and launched | Functional client E2E and physical ARM-device checks |
| Production release APKs/AAB | Flavors and release signing gate configured | User keystore variables, universal/ABI builds, R8/device tests |
| Backend executable | Go executable built; loopback readiness returned ready | More coverage, operational/load/security review |
| Compose | Full local stack healthy; patched Core image; persistence/idempotency survive stack restart | Target-VPS verification and load tests |
| Auth and multi-session | Backend invite/normalization/device-limit/reuse/recovery tests; Android Keystore, refresh and process-restart integration pass | Android account/session management UI and full multi-device flows |
| Discovery and group creation | Authenticated public-name search with privacy/block/bounds tests; native ConnectionsManager search and MessagesController creation pass; Persian group cache, member delivery and process restart verified | Screen-driven selection/creation, contacts, group settings/members, atomic create-time auto-delete, interrupted-create acknowledgement/retry |
| Group messages | REST permissions/mutations and Android repository/Telegram dialog adapter tests; server ordinals and pagination verified | Android wiring of all features, legacy debug UI-cache upgrade and large-conversation tests |
| E2EE private chat | Plaintext fails closed | Reviewed Signal library integration, prekeys, ratchets, verification, device changes, adversarial tests |
| Sync | Backend cursor/reconnect tests; Android group-text outbox survives real disconnect/process restart, preserves order and deduplicates lost acknowledgements; exclusive send claims, durable cancellation, schema upgrades and retention tested; SendMessagesHelper verifies native cleanup before journal acknowledgement and removes tombstoned committed sends without reviving them; native Telegram logout cannot erase a VYRN account; foreground WebSocket persists batches/cursor before ack and adapts incoming messages; 79 named Android checks pass | Screen-driven cancellation, account-switch races, concurrent deletion during reconciliation, all event mappings, crash/background recovery, queued media, full multi-device/UI tests |
| Media/voice | Authenticated upload/download and low-disk tests | Android media/voice UI, encryption and lifecycle tests |
| Profile/presence/block | Backend endpoints and websocket activity timestamps | Complete privacy controls and Android integration |
| Reactions/receipts | Mutations/read models and monotonic read-state integration tests pass | Android UI rendering |
| Search/drafts/disappearing | Backend routes and expiry/GC integration tests | Cross-device UI and local E2EE search |
| Admin | TOTP API and authenticated dashboard tested; Persian RTL inspected; metadata tables/actions implemented | Full action E2E, per-admin TOTP, flags and storage-limit controls |
| Push | Active websocket only | Background sync, native notifications, internal push, optional adapters |
| Updates | Ed25519 envelope library and tamper/expiry/rollback tests | Android verification/download/install and signed remote config |
| Backup | Local dump restored successfully to isolated database | External S3 configuration, encryption/retention/restore/alert proof, media backup |
| TURN/calls | Inactive template and profile | Provider inbound UDP, credentials, TLS renewal, WebRTC and relay testing |
| VPS/DNS/TLS | Configuration and guarded DNS script | SSH key installation, read-only audit, host hardening, DNS and live TLS |

Do not present VYRN as complete until every required production and end-to-end acceptance item is verified. Missing external inputs are separate from unfinished implementation work.
