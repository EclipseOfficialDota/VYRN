# Feature and release status

VYRN is a development preview. This table describes implemented paths and scoped evidence; it is not a promise of complete Telegram feature parity or a supported Stable release.

| Area | Implemented scope | Remaining limits |
| --- | --- | --- |
| Authentication | Invite registration, email/password login, recovery codes, device sessions, logout/revocation | No automatic email verification/delivery; full account/lifecycle acceptance incomplete |
| Group messages | Text, history, edits, deletion, reactions, receipts, event synchronization | Fresh two-client retest blocked by host emulator crashes |
| Private messages | Signal per-device keys, ciphertext delivery, native integration, verification paths | Independent security review, complete lifecycle/UI and Release acceptance outstanding |
| Persistence | SQLite mappings/inbox/outbox, durable retries, encrypted local private drafts | Arbitrary interruption/account races not exhaustively covered; no cloud draft sync |
| Groups | Native create/invite, confirmed title editing, basic owner/admin/member roles | Granular permissions and many upstream settings unavailable/gated |
| Media | File/photo/voice-message upload and download paths, validation and cancellation tests | Background, large workload and complete UI acceptance unfinished |
| Administration | Bilingual panel, TOTP, invitations, users, password reset, sessions, audit | Private vulnerability reporting enabled on GitHub; production support policy remains pending |
| Deployment | Containerized Core/PostgreSQL, HTTPS proxy templates, health checks | Each self-hosted instance needs its own DNS, credentials and operation checks |
| Backups | Encrypted S3-compatible scripts, checksums and isolated restore tools | Real offsite destination/transport/restore rehearsal required per installation |
| Updates | Signed manifest library and validation | Complete production Android update distribution/acceptance unfinished |
| Calls and other Telegram features | Voice/video calls, bots, stories, channels and group calls unavailable | Do not enable UI/ports as a substitute for implementation and acceptance |

## Before a Stable Android release

- Prepare the final keystore outside Git/VPS and sign actual production artifacts.
- Verify package identity, signature, native libraries, minSDK, public HTTPS endpoint and absence of the local test CA.
- Run runtime tests on the actual optimized Release; Debug checks cannot substitute.
- Complete reliable two-client, interrupted-network, account/lifecycle, media and background acceptance.
- Verify supported physical ARM/Android combinations in an authorized environment.
- Deliver corresponding sources and required third-party notices for the binary.
- Confirm production operation, external encrypted backup restoration and monitoring.

See [test evidence](TEST_REPORT.md), [signing](RELEASE_SIGNING.md), [self-hosting](DEPLOYMENT.md) and [source compliance](GPL_COMPLIANCE.md). Public source availability does not make the older Debug prerelease Stable.
