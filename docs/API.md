# VYRN Core API v1

All production endpoints require HTTPS. JSON uses UTF-8. Authentication is `Authorization: Bearer <access-token>`; secrets must never appear in URL query strings. Error bodies contain a generic `error` code. Access tokens expire after 900 seconds. Refresh tokens rotate and have a fixed 30-day session horizon.

| Method / path | Purpose |
|---|---|
| POST `/v1/auth/register` | email, password, invite_code, display_name, device_id UUID, device_name; returns tokens and a one-time-displayed recovery code |
| POST `/v1/auth/login` | email, password, device_id, device_name |
| POST `/v1/auth/refresh` | refresh_token; rotates both tokens |
| POST `/v1/auth/recover` | email, recovery_code, new_password; consumes code and revokes sessions |
| POST `/v1/auth/recovery-code` | authenticated password recheck; replaces recovery code |
| POST `/v1/auth/logout` | revokes current session |
| GET/PATCH `/v1/me` | profile; update display_name and owned avatar_id |
| GET `/v1/users/{id}` | profile by UUID, without email |
| GET `/v1/users/search?q=...&limit=50` | authenticated display-name discovery; maximum50 results, excludes self, disabled users and blocks in either direction; no email search or login fields |
| GET `/v1/sessions` | active sessions for current user |
| DELETE `/v1/sessions/{id}` | revoke an owned session |
| PUT/DELETE `/v1/blocks/{id}` | block/unblock another user |
| GET/POST `/v1/conversations` | list or create private/group conversation with members and title; groups accept optional disappearing_seconds from0 to31536000, committed atomically with members/events |
| GET `/v1/conversations/{id}` | member-authorized snapshot of conversation metadata and members with roles/joined_at; independent of the bounded conversation list; removed members and outsiders receive404 |
| PATCH `/v1/conversations/{id}` | title and/or disappearing_seconds; omitted fields are preserved atomically; an empty patch is invalid; zero explicitly disables auto-delete |
| GET/POST `/v1/conversations/{id}/members` | list or add/change member role; owner/admin permissions enforced; optional preserve_existing=true makes an authorized invitation leave an existing role unchanged atomically, while new members are inserted normally |
| DELETE `/v1/conversations/{id}/members/{user}` | remove member/leave group |
| GET `/v1/conversations/{id}/messages` | limit 1–100, before message UUID, optional plaintext group search q; live_only=true excludes deleted/expired messages before pagination; default preserves tombstones |
| POST `/v1/conversations/{id}/messages` | client_id, payload, encrypted, optional media_id/reply_to/forwarded_from |
| DELETE `/v1/conversations/{id}/messages` | atomic group deletion of1–100 distinct message UUIDs in JSON ids; member deletes own messages, owner/admin may delete other senders; returns204 after commit |
| GET `/v1/messages/{id}` | current authorized message state, including deletion/expiry tombstones |
| PATCH `/v1/messages/{id}` | payload, encrypted, expected revision; stale edit returns 409 |
| DELETE `/v1/messages/{id}` | replace message with a tombstone |
| PUT/DELETE `/v1/messages/{id}/reactions` | emoji |
| PUT `/v1/messages/{id}/receipt` | read boolean; delivered state is recorded |
| PUT `/v1/messages/{id}/pin` | pinned boolean, permission checked |
| GET/PUT `/v1/conversations/{id}/draft` | payload and encrypted flag |
| POST `/v1/conversations/{id}/typing` | throttled five-second event |
| GET `/v1/sync?cursor=0` | ordered events, next cursor, has_more |
| GET `/v1/ws?cursor=0` | WebSocket upgrade using Authorization header |
| PUT `/v1/conversations/{id}/sends/{client}/cancel` | atomically fence an unsent client ID or return its committed message ID |
| POST `/v1/media?kind=file&conversation_id=...` | raw bytes, Content-Length, optional X-Filename; encrypted=true for private attachments |
| GET `/v1/media/{id}` | authorized download with range support |
| GET `/health/live`, `/health/ready` | process and PostgreSQL readiness |

Plaintext group payload: `{"text":"Hello"}` (optional caption). Maximum combined text/caption length: 16,384 characters. Private plaintext returns `e2ee_required`; a ciphertext-envelope schema exists, but device-key endpoints and client cryptography remain incomplete. Cross-conversation forwarding is not yet implemented; current reference validation only accepts visible messages in the same conversation.

Message read models include a positive integer `ordinal`, allocated once by the server and stable across devices, edits, retries and restarts. History is ordered by descending ordinal; `before` still takes the corresponding message UUID. The Android adapter uses this ordinal for Telegram's UI message ID, preventing older pages from receiving larger IDs than recent messages. Reaction summaries contain `emoji`, `count`, `mine`; receipt summaries contain `delivered`, `read`, `delivered_by_me`, `read_by_me`. Deleted and collected-expiry tombstones have empty payloads and zero reaction/receipt summaries. An expired message awaiting collection returns no content.

Send cancellation is bound to the authenticated sender and the original conversation/client UUID. The cancellation endpoint returns `{"status":"cancelled"}` after committing a permanent client-ID fence, or `{"status":"committed","message_id":"..."}` if a send already committed. Repeated cancellation is idempotent. A delayed send for a fenced client ID returns409 `send_cancelled`. A committed message is retained; use the explicit message-deletion API to delete it. Sending and cancellation acquire the same transaction advisory lock, so an in-flight send cannot escape a successful cancellation. Cancellation fences are retained indefinitely to reject arbitrarily delayed offline replays; storage quota/operational controls and Android reconciliation remain acceptance work.

WebSocket first returns `{"type":"ready",...}`. Each `events` frame has ordered events and `cursor`. Commit events locally, then send `{"type":"ack","cursor":N}`. Heartbeat is 20 seconds; an unacknowledged batch disconnects after 45 seconds. Reconnect with bounded exponential backoff and the last committed cursor. A revoked/expired session closes the stream; refresh authentication and reconnect. Android foreground consumption now journals raw batches and cursor before acknowledgement and adapts message events. Background execution, crash recovery at every boundary and complete mapping of conversation/member/typing/reaction/receipt events remain acceptance work.

Uploads: file/video/image 250 MiB, avatar 10 MiB, voice 100 MiB, user quota 2 GiB. Low disk returns 507 while text sends stay available. An unbound upload is owner-only; other members can download only after attachment to a live authorized message.

## Group batch deletion

Group batch deletion accepts `{"ids":["message-uuid", "another-uuid"]}`. Every ID must belong to the authorized group; a missing or cross-group ID returns404 and an unauthorized sender returns403 without changing any selected message. Existing deletion/expiry tombstones are accepted as no-ops after authorization. Successful retries neither increase revisions nor emit duplicate deletion events. The conversation lock serializes membership changes; message locks use UUID order. Payload clearing, media-reference removal, revision increments and recipient events commit together. Android currently supports delete-for-everyone for normal group messages; delete-for-me, private/scheduled/channel deletion and durable offline deletion intent remain unfinished and are rejected before local cache removal.

## Administrator API

`POST /v1/admin/unlock` with a TOTP `code` returns a five-minute `admin_token`. Subsequent administrator routes require `X-VYRN-Admin-Token` as well as the normal access token. Routes: dashboard, users, groups, invites, audit; user disable, password reset, session revocation and invite creation/revocation. Public API virtual hosts block all `/v1/admin/*`; use the restricted administrator hostname.

List pagination currently caps conversations at 500 and administrator lists at 250. Complete cursor pagination for these collections remains an acceptance gap; do not infer full production API coverage from this table.
