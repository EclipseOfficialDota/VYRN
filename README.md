# VYRN
<p align="center"><img src="assets/vyrn-logo-transparent-1024.png" width="112" alt="VYRN logo"></p>

**Self-hosted messaging with a Telegram-derived Android client, a Go backend and a bilingual administration panel.**

[![CI](https://github.com/EclipseOfficialDota/VYRN/actions/workflows/ci.yml/badge.svg)](https://github.com/EclipseOfficialDota/VYRN/actions/workflows/ci.yml)
[Self-hosting](docs/DEPLOYMENT.md) · [راهنمای فارسی](docs/OWNER_GUIDE.fa.md) · [API](docs/API.md) · [Architecture](docs/ARCHITECTURE.md) · [Contributing](CONTRIBUTING.md)

> **Development preview.** Public source is available; a supported Stable Android Release is not available yet. The existing Debug prerelease is an older snapshot. See [supported features and limits](docs/ACCEPTANCE.md) and [test evidence](docs/TEST_REPORT.md).

## What it does

VYRN runs an independent messaging backend. It does not connect accounts to Telegram or federate with other VYRN servers. The Android client reuses pinned Telegram UI sources with VYRN branding and an adapter for the independent API.

- Invite-based email/password registration, recovery codes and device sessions.
- Group messaging, private-message encryption using Signal, edits, deletion, reactions and read receipts.
- Durable local message queues, reconnect handling, encrypted private drafts and local search.
- File, photo and voice-message paths with server-side size and storage limits.
- VYRN Control: English/Persian administration, TOTP verification, invitations, user blocking, password resets, session revocation and audit events.
- Docker Compose services for PostgreSQL, Core and a Caddy HTTPS edge; encrypted S3-compatible backup tooling.

Implemented paths have targeted tests. Feature presence does not imply complete UI, lifecycle, security-audit or production acceptance. Voice/video **calls**, bots, stories and channels are unavailable; voice messages are separate from calls.

## Host your own instance

Use a dedicated Linux host, a domain you control and Docker Engine with Compose. The [full guide](docs/DEPLOYMENT.md) covers DNS/TLS, private configuration, first administrator, health checks, backups and upgrades. It includes commands for a fresh Ubuntu host. Installation on a host with an existing reverse proxy needs the separate proxy configuration.

1. Clone this repository.
2. Configure your own service domain and secrets in `/etc/vyrn/runtime.env`.
3. Build Core, start PostgreSQL and bootstrap the first administrator.
4. Start Core/Caddy, sign in to Control, and issue invitations.
5. Build an Android client targeting **your** HTTPS API and distribute it with its matching source.

The default downloaded development APK targets the project's instance. It cannot be redirected to your server through a login setting. A custom client build with `-ApiUrl` is required. Accounts are local to each installation.

## Android build

Build scripts currently target **Windows with PowerShell 7**, Git, JDK21 and the Android SDK/NDK. Build the server on Linux and the client on a build workstation; do not copy your final keystore to the server.

```powershell
./android/fetch-upstream.ps1
./scripts/setup-jdk.ps1
# Install Android SDK platform36, build-tools36.0.0 and NDK27.2.12479018.
./scripts/build-android.ps1 -Flavor dev -BuildType debug -ApiUrl https://api.vyrn.example.com
./scripts/verify-apks.ps1
```

Replace the example API hostname with your instance. The upstream pin and its submodules are fetched by the preparation scripts. Outputs are in ignored `artifacts/`; normal builds produce ARM64, ARMv7 and Universal APKs, minimum Android8/API26. See [Android development/testing](docs/ANDROID_TESTING.md), [final signing](docs/RELEASE_SIGNING.md) and [source redistribution](docs/GPL_COMPLIANCE.md).

## Documentation

| Guide | Contents |
| --- | --- |
| [Self-hosting](docs/DEPLOYMENT.md) | Fresh server, Docker, domains, configuration, bootstrap and maintenance |
| [Owner and user guide — فارسی](docs/OWNER_GUIDE.fa.md) | نصب، ورود مدیر، دعوت کاربر، استفاده و رفع خطا |
| [Architecture](docs/ARCHITECTURE.md) | Client adapter, API, storage, authentication and trust boundaries |
| [API reference](docs/API.md) | Requests, authentication, errors and limits |
| [Feature status](docs/ACCEPTANCE.md) | Implemented paths and outstanding release gates |
| [Test evidence](docs/TEST_REPORT.md) | What passed, what failed and scope limits |
| [Backup/restore](docs/BACKUP_RESTORE.md) | External encrypted backup and isolated restore rehearsal |
| [Security](SECURITY.md) | Reporting and support status |
| [Portfolio summary](docs/PORTFOLIO.md) | Project description for a résumé or showcase |

## Repository map

```text
android/       Android app, compatibility adapter and pinned upstream build scripts
backend/       Go REST/WebSocket service, PostgreSQL migrations and tests
admin/         English/Persian web administration
deployment/    Compose, Caddy, optional proxy and backup service templates
scripts/       Build, validation, test, backup and restore tools
docs/          Setup, operation, architecture and acceptance
assets/        VYRN branding assets
```

Generated upstream checkouts, SDK/JDK installations, caches, credentials, environments, APKs, private media and backups are excluded from Git. The configuration in `config/project.yaml` is descriptive; Compose reads the operator's runtime environment.

## License and attribution

VYRN is independent of and unaffiliated with Telegram. Android UI work derives from [DrKLO/Telegram](https://github.com/DrKLO/Telegram), pinned in `android/upstream.lock.json`; it is not an original UI implementation. Preserve upstream copyright notices. See [LICENSE](LICENSE), [NOTICE](NOTICE.md), [Android notices](android/NOTICE.md) and [license/source compliance](docs/GPL_COMPLIANCE.md), including libsignal's separate AGPL obligations. A complete binary/source/license audit remains a release gate.
