# VYRN project memory

Updated 2026-10-01. Development is unfinished. No production release or VPS deployment is claimed.

## Authoritative decisions
- Root: C:/Users/PC/Documents/ChatGPT/Telegram/VYRN; parent workspace preserved.
- Local Git only: main/develop and current codex/initial-implementation. No remote.
- Read docs/REQUIREMENTS.fa.md, config/project.yaml and docs/DECISIONS.md before each new stage.
- Telegram pin f2908b14133bbffbf7ab04f641ecb5bfaf533242, 12.10.6/7112. Actual original UI compiles; not a lookalike replacement.
- VPS 217.18.90.114, expected Ubuntu24.04.3, root/3939, 4GB/40GB, Asia/Tehran. Never disable root/password SSH without explicit permission.
- Calls OFF; inbound UDP unconfirmed. Logo remains a temporary text placeholder.
- Cloudflare process variables absent. Only production DNS work stops; use scripts/set-cloudflare-env.ps1 privately.
- Final release waits for VYRN_KEYSTORE_PATH, VYRN_KEY_ALIAS, VYRN_STORE_PASSWORD, VYRN_KEY_PASSWORD. No release key in Git/VPS.
- External backup destination absent. Local backups are temporary and unreliable.

## Implemented and verified
- Go monolith: migrations, invite/email auth, Argon2id, three-device limit, rotating hashed tokens/reuse revocation/recovery, group messaging/permissions/idempotency, edits/tombstones, reactions/receipt read models, pin/drafts/search, authorized media, quota/disk reserve, expiry and seven-day orphan GC, websocket ack/resume, admin TOTP/grants/audit, signed update envelope.
- Seven PostgreSQL integration tests pass, plus security/update unit tests: concurrent sends, offline websocket resume, expiry/GC, permissions and media access.
- Go upgraded from1.26.4 to pinned1.26.8 after govulncheck found five standard-library issues. Re-scan: zero reachable/imported-package vulnerabilities; unused x/crypto/openpgp module advisory GO-2026-5932 remains (OpenPGP not imported).
- Core image built with pinned Go1.26.8. Compose actually started, Core/PostgreSQL healthy; fixed YAML tmpfs parsing discovered at runtime.
- Local restart test preserved sessions/messages/events and idempotent retry, repeated with patched image.
- Local database dump restored to isolated database. External encrypted backup/restore unverified.
- Caddy config validated in actual image. No public DNS/ACME or VPS mutation.
- Admin login, TOTP, dashboard and Persian RTL group page checked in browser. Screenshot artifacts/admin-fa.png. Browser refresh rotation now serialized.
- Android SDK/JDK17/NDK27.2/CMake installed locally; exact upstream submodules present. dev Debug ARM64, ARMv7 and combined Universal APKs built. Signatures, package xyz.eclipseofficial.vyrn.dev, minAPI26, target36, ABI contents, cleartext off/backup off/RTL on verified by scripts/verify-apks.ps1. Large unminified debug artifacts.
- Dedicated key .local/ssh/vyrn_ed25519; SSH key login rejected. Install only its .pub via provider console/existing session; independently verify host fingerprint. No remote command ran.

## Local runtime and commands
- Native Core http://127.0.0.1:8080; PID in .local/core.pid, artifacts/vyrn-core.exe. Verify exact process path before stopping.
- Native development PostgreSQL container vyrn-postgres-dev, loopback55432, persistent volume.
- Separate Docker smoke project vyrn-local at http://127.0.0.1:18080; compose.yaml + compose.local.yaml; separate PostgreSQL/media volumes. Local override profile-gates Caddy.
- .local/dev.env contains generated credentials: never print values. init-local.ps1 preserves it.
- From backend: ../scripts/with-local.ps1 -Command go -Arguments @('test','./...','-count=1'). Explicit Arguments avoids PowerShell consuming Go flags.
- Android: scripts/build-android.ps1; defaults arm64-v8a,armeabi-v7a. HTTPS hostname required. Log .local/android-build.log. APK defaults to production HTTPS and cannot connect before that endpoint exists.
- Emulator: scripts/setup-emulator.ps1; Android35 default x86_64, local AVD vyrn_api35. WHPX verified. scripts/test-android-smoke.ps1 passed installation, launcher resolution, two cold launches/process restarts and no VYRN crash during observation. Report artifacts/android-smoke.json. This does not verify functional login, messaging or physical ARM hardware.
- RTK absent; direct commands used. Some network tools need Windows system proxy explicitly, preserving TLS validation. Docker/ADB may need normal-account escalation. Do not alter global security settings.

## Unfinished implementation, separate from external gates
1. Android discovery, group creation/settings/members, profile/session/recovery, media/voice, edit/delete/reactions/drafts/receipts. Current adapter supports login, dialogs/history/group text/typing; other upstream requests fail unavailable.
2. Durable Android inbox/outbox, websocket reconnect, ack after local persistence, process-restart/multi-device tests. Backend sync is not client sync.
3. Reviewed Signal library, device/prekey API, identity verification/rotation and adversarial crypto tests. Private chat is not ready; plaintext rejected. Envelope schema is not E2EE completion.
4. Background sync/internal push, notifications, Android signed update/download/install and remote config.
5. Admin operational controls/metrics, per-admin TOTP, pagination/load/security review, full branding/source audit, media-inclusive external backup proof.
6. Complete Android functional E2E and physical-device tests; R8/release APK/AAB checks. Emulator startup smoke passed, full product acceptance has not.
7. After prerequisites: VPS read-only audit/hardening, restricted admin, DNS/TLS, encrypted external backup, final logo/signing, gated TURN.

Keep docs/ACCEPTANCE.md and docs/TEST_REPORT.md aligned with actual results. Product is complete only when every required production and end-to-end acceptance criterion passes.
