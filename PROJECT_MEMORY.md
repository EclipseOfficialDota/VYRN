# VYRN project memory

Last updated: 2026-09-30. Implementation in progress; no release or deployment verified.

## Authoritative configuration
- Project root: `C:/Users/PC/Documents/ChatGPT/Telegram/VYRN` (explicit user choice; parent files preserved).
- Local Git, no remote; main and develop, work branches codex/*.
- Full specification: docs/REQUIREMENTS.fa.md; confirmed decisions: docs/DECISIONS.md.
- VPS: 217.18.90.114, expected Ubuntu 24.04.3 LTS, root, SSH port 3939, Asia/Tehran; 4 GB / 40 GB.
- Generate a dedicated local SSH key. Existing root login must remain enabled; never disable root/password authentication without explicit authorization.
- Cloudflare variables absent in the current environment; DNS operations blocked. Never print secrets.
- Final logo missing: use clearly labelled VYRN text placeholder.
- Debug signing allowed. Release signing waits for VYRN_KEYSTORE_PATH, VYRN_KEY_ALIAS, VYRN_STORE_PASSWORD, VYRN_KEY_PASSWORD; no key on VPS or in Git.
- No external backup destination: prepare encrypted S3-compatible backup/restore; local backups are temporary and unreliable.
- Provider inbound UDP not confirmed: production voice/video calls disabled.
- Missing production inputs must not block local backend, Compose, tests, or debug APK work.

## Existing work
- Parent workspace contains a clean Telegram checkout and unfinished Go source. Copied source into this project without modifying parent work.
- Telegram pin: f2908b14133bbffbf7ab04f641ecb5bfaf533242, version 12.10.6 / 7112.
- RTK unavailable on PATH; direct commands used until available.
- Go, Git, Java, Docker CLI, Node, OpenSSH present. Android SDK and accessible Docker engine need setup/verification.

## Verification and next steps
1. Establish reproducible Android toolchain/build and compatibility entry point.
2. Complete backend, migrations, integration tests and deployment tooling.
3. Connect Telegram UI, audited E2EE and multi-device functionality.
4. Complete remaining specification and record actual test results.

Production DNS/TLS, SSH provisioning, final signing, external backups and calls remain gated by their actual prerequisites. Do not mark the project complete until all acceptance criteria pass.
