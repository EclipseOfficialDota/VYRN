# Architecture

## Android boundaries

Telegram's original Java UI, themes, native media and navigation remain in the pinned checkout. `VyrnBridge` is the isolated adapter that understands Telegram's `TLRPC` UI models. `domain/Models` defines VYRN users, conversations, messages and the repository contract. `data/RestRepository` translates REST JSON into these models. A persistent SQLite ID map prevents backend UUIDs from spreading into Telegram's integer-based UI.

The bridge handles dialog/history retrieval, group text send and foreground message-event updates; unsupported requests fail explicitly. A dedicated email login fragment uses Telegram's navigation and themed text controls. Original phone login is bypassed at launch. Complete migration of Settings/Profile/Media/Groups, removal of unsupported product surfaces and complete realtime UI updates remain necessary.

Session tokens are AES-GCM encrypted with a non-exportable Android Keystore key. Device IDs and the UUID map persist independently. HTTP is disabled. Hostname and certificate validation use the platform defaults; no trust-all manager exists. Telegram's native socket connection path is disabled to avoid accidental use of Telegram servers.

## VYRN Core

Go modular monolith, `net/http`, PostgreSQL via pgx, coder/websocket, local filesystem storage behind a stream-oriented interface. No Redis. Caddy terminates public TLS and limits administrator network access. Local and production media directories are outside source control.

Mutations run in transactions. Conversation-row locks serialize membership changes and sends. A transaction advisory lock serializes event sequence allocation through commit, preventing a lower cursor committing after a higher cursor was observed. Message client IDs have a `(sender_id, client_id)` uniqueness constraint and request hash. An identical retry returns the original ID; changed content returns 409.

WebSocket batches contain at most 100 ordered events. The server sends one unacknowledged batch at a time and disconnects slow clients. REST sync provides the same cursor stream after reconnect. Authentication is checked during the connection, not only at upgrade. Durable events contain resource references, avoiding duplicate plaintext bodies in event history. Clients must fetch authorized resources and commit local state before acknowledging.

Android stores raw event references and the resume cursor in one per-account SQLite transaction before sending the WebSocket acknowledgement. A single-thread worker applies entries in order, retries transient adapter failures with bounded backoff and retains poison entries as dead letters while allowing later events to proceed. Processed journal retention is bounded. The current bridge applies message create/edit/delete references; other event kinds still require complete Telegram model mappings.

Media uploads enforce membership, ownership, type limits, user quota, and the free-space reserve. Private attachments must be ciphertext. Downloads re-check current membership and live message references; an unbound upload is readable only by its owner. Files use generated UUID storage keys, never user filenames. Orphans receive a retention timestamp and are collected after seven days.

## Production envelope

Compose allocates 768 MiB each to Core and PostgreSQL, 192 MiB to Caddy, and 256 MiB to Coturn only when explicitly enabled. PostgreSQL has no public port. Core is read-only except its media mount and tmpfs. Log rotation is bounded. Runtime dependencies are local after images are obtained; public certificate renewal still requires ACME reachability before expiry.

Private E2EE is not yet implemented on Android. Backend ciphertext envelopes and key-table schema are not an encryption protocol. The current client must not send private plaintext or be described as providing completed E2EE.
