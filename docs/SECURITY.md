# Security status and boundaries

## Implemented controls

- Invite-only normalized email identifiers; no email transport or OTP.
- Argon2id with 64 MiB, three iterations, one lane, random 16-byte salt. Concurrent password work is bounded.
- Generic login/registration failures, IP rate limits and per-identifier temporary locks.
- 15-minute access tokens, 30-day refresh horizon, opaque random tokens stored only as SHA-256 digests.
- Refresh rotation with reuse-triggered session revocation; recovery codes are hashed and consumed once.
- Per-user lock around session creation; maximum three active device sessions.
- TOTP administrator unlock, single-use time step, five-minute grant bound to an authenticated session; audit metadata excludes secrets and message content.
- Membership-based permissions, quota and disk reserve checks, private media authorization and path-safe UUID storage keys.
- Android token encryption through Android Keystore and standard AES-GCM; production/Android cleartext disabled.
- Authenticated device-only public-key publication, immutable identity/address binding, optimistic key versions, key-ID reuse/rotation-rollback rejection and atomic one-time EC prekey consumption. The server validates wire shape; receiving libsignal clients must verify signatures. Active unprepared devices are visible in the authorized roster. No private keys are stored by these endpoints.
- Android initial-publication/claim services bind the user and original session through refresh, persist private keys before HTTP and recover the same publication after acknowledgement failure. Real identity signatures and strict owner/device/version/key encodings are checked before constructing a claimed Signal bundle.20 actual HTTPS/JNI checks pass, including exhausted EC keys with last-resort Kyber decryption.
- Private signal-v2 sends/edits bind the authenticated producing device and current Signal-address/key-version roster. User/session/key locks preserve complete ready-device coverage through commit; unprepared, missing, extra, stale or mismatched copies fail closed. Real POST/GET delivery and decryption of distinct copies on two recipient stores pass27 Android network checks. These are service checks on one emulator, not private UI acceptance.
- Signed update envelope library rejects tampering, expired metadata, version rollback and unauthorized APK hosts.

## Incomplete controls — release blockers

The pinned official Signal JNI and account/device-scoped Android Keystore-encrypted SQLite store pass27 real cryptographic/storage/restart checks. Authenticated public-key publication, metadata reads, atomic one-time claims and versioned server fanout pass PostgreSQL privacy/concurrency/rollback tests. Signature verification occurs in the receiving Signal client; server fixtures only validate wire shape. The storage transaction can couple ratchet advancement and application ciphertext/cache records, but the private-chat network outbox, encrypted receiver/cache workflow and verification UI are not integrated.

End-to-end Signal integration, authenticated inner conversation/client/revision metadata, identity verification, automatic publication/refill/key rotation, complete client multi-device encrypted fanout and full adversarial device/network lifecycle tests are not complete. Private plaintext is rejected by the backend and private sending is disabled in the initial bridge. The presence of an `encrypted` flag is never proof of E2EE.

Android background sync, durable acknowledgement handling, media encryption, signed remote configuration, updater integration, full logout key cleanup and device-revocation UI require completion. Unsupported Telegram surfaces need removal or VYRN implementations. No production penetration/security review has been claimed.

Admin TOTP currently uses one deployment secret for the bootstrap administrator. Per-administrator enrollment and recovery are required before adding further administrators. Control displays only metadata.

## Operational boundaries

The dedicated SSH key is `.local/ssh/vyrn_ed25519`; its public half may be installed through the VPS provider console. The private file remains local and ignored. First-seen SSH host identity is stored in `.local/known_hosts`; compare its fingerprint with the provider before administrative changes. The server rejected the new key during the read-only attempt, so no host changes ran.

Never disable root login or password authentication without explicit user permission. Never commit `.env`, keystores, service JSON, credential files, backups or private keys. Do not write secrets into process arguments, logs, reports, screenshots or shell history. Root/password access settings are preserved.

Actual values belong in environment variables or restricted ignored files. Source and release archives must be independently checked for secret inclusion. API logs report operation categories only. Certificate verification must never be disabled to work around local or national connectivity failures.
