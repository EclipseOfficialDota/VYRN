# Rust backend rewrite

Status: **in progress, not a replacement server**. The Go backend, original deployment
and Docker Lite remain available until the Rust implementation passes compatibility
and resource measurements. No existing installation or database is changed by this branch.

## What stays compatible

- The current Android UI, branding and Java/C++ Telegram-derived client.
- Existing `/v1` requests, responses, errors and WebSocket event formats.
- Existing PostgreSQL data and migrations, accounts and session token digests.
- Current Signal integration, administrator authorization and authenticator checks.

Keeping the Android client native preserves its behavior; moving the server to Rust
does not require rewriting the Android UI. The static administration panel also needs
no additional server runtime. Calls remain disabled until inbound UDP is verified.

## Implemented first slice

`backend-rust` contains compatible Argon2id password hashing/verification, random
session tokens and SHA-256 token digests. The maintenance CLI runs these operations;
it does **not** accept `serve` or expose an incomplete HTTP API.

The Rust CI job runs on GitHub, one compilation worker and one test thread. It tests
Go-generated hashes in Rust and Rust-generated hashes in Go, including Persian,
whitespace and maximum-length passwords. It also checks session token/digest
compatibility and rejects invalid UTF-8, excessive input and hostile hash parameters.
No dependencies or build tools need to be downloaded to the owner's computer.

Password hashing still uses 64 MiB, three passes and one lane, matching existing
accounts. Lowering that cost to advertise a smaller RAM number would weaken account
security. The future HTTP login path must admit only one concurrent password hash on
small instances and bound waiting requests before allocating request bodies.

Developer checks (on a build machine with Rust and Go installed):

```sh
cd backend-rust
cargo test -- --test-threads=1
cargo build --release -j 1
cd ../backend
VYRN_RUST_BINARY="$PWD/../backend-rust/target/release/vyrn-core" \
  go test -p 1 ./internal/security -run TestRustCompatibility -count=1
```

## Remaining before a server cutover

1. Database connections/migration locking, authentication, invitations, recovery,
   session revocation, throttling and administrator TOTP.
2. Profile/discovery/blocking, conversations, messages, Signal device keys,
   receipts/reactions/drafts, sync and WebSockets.
3. Bounded media streaming/uploads, quotas/free-space checks, background cleanup,
   static admin hosting and signed update tooling.
4. Docker image and installer support, backup/restore checks and the same Android
   integration tests against the Rust API.
5. Compare Go and Rust with identical data, load, container limits and TLS proxy:
   idle/active RSS, password hashing peak, p95 latency, CPU and image size. Measure
   PostgreSQL and proxy separately and report the complete stack as well.

No resource saving or minimum server specification is claimed yet. PostgreSQL,
TLS and uploaded media consume resources regardless of the backend language.
Cutover requires a verified backup and a rollback path; do not run both writers
against a production database during migration testing.
