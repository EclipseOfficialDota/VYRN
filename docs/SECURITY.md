# Security design and limits

VYRN is a development preview, not an independently audited or supported production messenger. See [release gates](ACCEPTANCE.md) and [reporting](../SECURITY.md).

## Authentication and administration

Email identifiers are normalized; registration requires an invitation. There is no email transport or email OTP service. Passwords use Argon2id with random salts, bounded concurrent work and generic login failures. Login paths have rate limits and temporary identifier locks. Access tokens are short-lived; refresh rotation detects reuse. Recovery codes are hashed and consumed once.

Administrative operations require an authenticated administrator and a five-minute TOTP grant bound to that session. Time steps are single-use. Audit records contain operation/target metadata, not passwords or message contents. The deployment TOTP secret belongs to the bootstrap administrator setup; independent enrollment/recovery for multiple administrators is unfinished.

Control tokens remain in browser memory. Caddy restricts the panel by operator CIDR, applies a content-security policy and blocks admin/metrics routes on public API hosts. Configure your own allowed Origin and domain. PostgreSQL and the internal Core listener must not be exposed directly.

## Message and device boundaries

Private messaging integrates pinned Signal JNI, authenticated per-device key exchange, encrypted local stores and ciphertext fanout. Android Keystore protects local key/token material. Targeted native/network/process-restart checks exist; full lifecycle, identity-change, media/background behavior and independent security review remain unfinished. Signal integration is not a complete security audit.

Group messages and service metadata are not private-message ciphertext. Server operators can access server-side group data and metadata; Control is not a private-plaintext viewer. Device compromise, screenshots, traffic analysis and malicious modified clients are outside these guarantees. Password reset cannot recreate lost device encryption keys.

Membership and ownership checks protect message/media access. Mutations are transactional; client IDs support idempotent retries. Media uses generated storage keys, size/type/quota checks, authorization and free-space limits. Downloads require a live authorized reference. Private attachments must be ciphertext; complete media E2EE acceptance is not claimed.

## Operations

Use valid HTTPS certificates and preserve certificate/hostname checks. The opt-in local CA belongs only in disposable emulator Debug builds, never normal distributed APKs.

Each operator supplies SSH identity, host-key verification, credentials and optional DNS-provider tokens. Preserve working SSH access during policy changes. Store runtime configuration with restrictive permissions outside Git; keep final Android signing keys off the VPS and retain an offline copy.

Never publish secrets, real environments, keystores, backups or private media in source, logs, screenshots or issues. Pattern scanners do not prove every possible secret absent. Inspect source and binary artifacts separately.

Backups need authenticated encrypted offsite storage and a tested restore; same-host snapshots are temporary. Calls/TURN remain unavailable. Signed manifest primitives exist, but complete Android update delivery is unfinished; automatic secure updates are not claimed.
