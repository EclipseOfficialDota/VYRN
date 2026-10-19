# VYRN

Independent messaging for `eclipseofficial.xyz`, based on the actual Telegram Android UI.

**Status: development in progress. This is not a production release.** See [test evidence](docs/TEST_REPORT.md) and [remaining acceptance criteria](docs/ACCEPTANCE.md). Private-chat encryption, complete Android feature wiring, production deployment and release artifacts must pass their gates before public use.

Latest Android checkpoint: [basic-group roles and confirmed settings](docs/checkpoints/2026-10-19-group-settings.md), with72 group and107 reaction/receipt checks. These changes are in source and local-TLS test builds; the downloadable ARM prerelease below predates them.

## Repository

`android/` contains the pinned upstream descriptor, reproducible preparation script, app module and compatibility/domain/repository layers. `backend/` contains the Go service and PostgreSQL migrations. `admin/` is VYRN Control. `deployment/` contains Compose, Caddy, inactive TURN configuration and backup timers.

Source repository: [EclipseOfficialDota/VYRN](https://github.com/EclipseOfficialDota/VYRN) (private). The generated `android/upstream/`, SDK/JDK, caches, local credentials and build artifacts are intentionally ignored. Main branches are `main`, `develop`; active work uses the `codex/` prefix.

For testing, download the approximately159MB Universal ARM APK from the [Debug prerelease](https://github.com/EclipseOfficialDota/VYRN/releases/tag/debug-20261019). It includes ARM64 and ARMv7, requires Android8.0 or newer, and is not the final signed production Release. SHA256SUMS.txt accompanies the APK.

## Local development on Windows

Use PowerShell 7, Go 1.26.9, Git and Docker Desktop. The module requires the patched toolchain. No production credential is needed.

```powershell
./scripts/init-local.ps1
docker run -d --name vyrn-postgres-dev --env-file .local/dev.env -p 127.0.0.1:55432:5432 -v vyrn-postgres-dev:/var/lib/postgresql/data postgres:17-alpine
Set-Location backend
../scripts/with-local.ps1 -Command go -Arguments @('test','./...','-count=1')
../scripts/with-local.ps1 -Command go -Arguments @('build','-o','../artifacts/vyrn-core.exe','./cmd/vyrn')
Set-Location ..
./scripts/with-local.ps1 -Command ./artifacts/vyrn-core.exe -Arguments @('bootstrap')
./scripts/start-local.ps1
```

Bootstrap requires an empty database and reads generated local settings; it never prints passwords or invite codes. Local credentials are in `.local/dev.env`, which must stay private. A production administrator requires an authenticator secret. HTTP here is loopback-only; Android and production endpoints require HTTPS.

Open VYRN Control at `http://127.0.0.1:8080`. The same origin serves the API. Browser tokens stay in memory; only a non-secret device identifier is stored locally.

## Android

Upstream pin: `f2908b14133bbffbf7ab04f641ecb5bfaf533242` (12.10.6 / 7112). Initialize its exact submodules before building. `android/prepare-upstream.ps1` applies VYRN changes without replacing Telegram's UI implementation. Telegram socket transport is disabled at `Connection::connect`; supported UI requests and foreground VYRN message events go through `VyrnBridge`.

```powershell
./android/fetch-upstream.ps1
./scripts/setup-jdk.ps1
./scripts/build-android.ps1 -Flavor dev -BuildType debug
./scripts/verify-apks.ps1
```

This builds a development client. The API endpoint defaults to the deployed HTTPS backend at `https://api.vyrn.eclipseofficial.xyz`. API URL overrides must be HTTPS hostnames. Debug signing does not imply release readiness. See [the implementation matrix](docs/ACCEPTANCE.md) for supported requests and gaps.

Verified ARM64, ARMv7 and Universal Debug APKs are local ignored files in `artifacts/`; hashes and manifest evidence are in `artifacts/apk-verification.json`. The last normal Universal ARM build is about159MB; the newer local-TLS emulator build is test-only. APKs are not committed to this repository.

For real emulator-to-backend HTTPS tests, see [Android integration instructions](docs/ANDROID_TESTING.md). The opt-in local CA is never included in normal APKs. Foreground repository/adapter tests and process-restart checks pass; complete interactive UI acceptance remains unfinished.

The independent Docker smoke stack uses `docker compose --project-name vyrn-local --env-file .local/dev.env -f deployment/compose.yaml -f deployment/compose.local.yaml up -d --build --wait`. It exposes only `127.0.0.1:18080`, keeps PostgreSQL private, and does not start Caddy or request public certificates. Bootstrap this separate empty database before using `scripts/test-local-stack.ps1`; that test restarts only the named local stack and verifies messages/events and idempotency.

## Production

Follow [deployment instructions](docs/DEPLOYMENT.md) for the active backend release and rollback details. External backups, final Android signing and complete release acceptance remain unfinished. Same-server snapshots are temporary and do not replace offsite backup. Do not disable root/password SSH authentication without the user's explicit authorization. See [security](docs/SECURITY.md), [backup and restore](docs/BACKUP_RESTORE.md), [updates](docs/UPDATE_PROCESS.md) and [source redistribution](docs/GPL_COMPLIANCE.md).

## Repository checks

See [contributing](CONTRIBUTING.md). GitHub CI definitions cover repository hygiene, script syntax, backend race/integration tests, vulnerability scanning and Docker compilation. GitHub execution is pending repository creation and authentication. Android APKs remain locally built; do not infer Android acceptance or production readiness from backend CI alone.
