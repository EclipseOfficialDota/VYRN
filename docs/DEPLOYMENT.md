# Deployment runbook

## Go security rollout — 2026-10-19

GitHub main now requires Go1.26.9 and pins the official golang:1.26.9-alpine image to sha256:3082400e369fa24d5fc60bca20edab3f6d604e0c5a690ec66b295eff4dd87ade. The initial GitHub vulnerability scan found eight reachable standard-library advisories in Go1.26.8; all report Go1.26.9 as fixed. Source commit b3241d130c79952ad8e74e8f9b673dc596ee9930 passes [CI](https://github.com/EclipseOfficialDota/VYRN/actions/runs/39291176819), including PostgreSQL/race tests, govulncheck and the production-container build. See the [Go advisory](https://pkg.go.dev/vuln/GO-2026-6617).

The Go1.26.9 binary from clean source28aa513 is now active after an isolated database/media restore rehearsal, a fresh activation snapshot, exact image/binary verification and nine authenticated public HTTPS checks. Server evidence below supersedes the earlier source-only checkpoint.

Status (2026-10-19): **Core source28aa513 is active with Go1.26.9, fresh authorization after lock waits and atomic reaction selection. Schema remains9; Core/PostgreSQL and authenticated public HTTPS checks pass. The Debug Android client has a supported standard reaction chooser; animated/custom/paid controls remain gated. Physical-phone and final Release acceptance remain pending.** See [Persian owner guide](OWNER_GUIDE.fa.md).

## Active deployment

The existing Ubuntu/Docker/Compose deployment is retained. The active release is /opt/vyrn/releases/core-20261019-28aa513, selected by /opt/vyrn/current. Use both deployment/compose.yaml and deployment/compose.existing-nginx.yaml with protected /etc/vyrn/runtime.env. Core binds only127.0.0.1:18080; PostgreSQL is unpublished. Existing Nginx retains ports80/443 and the owner panel binds only127.0.0.1:19091. Caddy's standalone-proxy profile remains excluded.

Current source:28aa513da7ccf2470e9ce48f7f32c76d604a6de7, clean VCS build using Go1.26.9, Linux/amd64, CGO disabled and trimpath. Running executable SHA25664f614c0c0176e6d769d201c3933d944a33a6971b91deb0691826a276b8cb59f; immutable image sha256:099e7bb99e880e187eb8e942a26423407246d2de5fc9427781f1e91b75d666b7, tagged vyrn-core:28aa513 and latest. All migration files and Compose/prebuilt Dockerfile were compared unchanged; schema stayed9. The identical backend tree passed GitHub PostgreSQL/race tests, vet, govulncheck and container build under Go1.26.9 at sourceb3241d1; later changes through28aa513 are documentation only. Release archive SHA2567a62e96232a4ff0d37c9edf54ca04cf34cb6c740bd6d701deba8af92381962e0,5193658 bytes.

Protected rehearsal evidence is under /opt/vyrn/operations/upgrade-20261019-28aa513. A consistent snapshot was restored to a separate database/private media volume: candidate startup, schema9, UID10001 and the2 media/part files passed, PREPARE_EXIT=0. Candidate resources/env were removed and independently checked absent before activation. Activation at /opt/vyrn/operations/deploy-20261019-28aa513 used another fresh validated snapshot and automatic code rollback on failure; its DEPLOYED result and exact running image/binary were read back. Public IPv4 was independently resolved from Windows, and the VPS HTTPS readiness probe retained normal hostname/certificate validation with --resolve. No DNS/resolver/TLS policy changed. Nine authenticated public HTTPS checks passed at2026-10-19T17:54:14.163830Z: selection replacement/replay/clear/invalid input/legacy routes, deletion denial and login/logout. The synthetic self-only message was deleted and verification session logged out.

Only Core was recreated. PostgreSQL stayed healthy with the same start timestamp observed before this rollout,2026-10-17T13:39:00.88699932Z. The earlier October4 start timestamp belongs to the older checkpoint; this rollout does not claim an observed whole-host reboot test. Both snapshot runtime.env files are root-owned0600. All same-VPS snapshots remain temporary and unreliable as offsite disaster backups. Owner loopback HTTP returns200; public metrics/admin routes remain404.

## Previous schema upgrade (2026-10-15)

The October15 release used source e66e6a892643d6e5ca386d0ac5bbee2e89316d59, executable SHA256c95b3454eef1f76d7fc9ea218826f0ec6df9ab6f4e84fed87f7dc387a2275846 and image sha256:0332c2f7887d52ec62648b1cc455d514da65b07f55dee2d738fe9b912d9ec503. It was tagged latest at that time. Migrations008_media_uploads.sql and009_voice_duration.sql upgraded schema7 to9; prior migrations were byte-compared unchanged. See the historical test report for that rollout.

Before activation, a consistent database/media/config snapshot was restored into a disposable database and private media volume. Candidate startup applied migrations7→9 and passed readiness, media size/checksum and UID10001 checks while live schema remained7. The first rehearsal attempt used the edge network and could not reach PostgreSQL; the successful repeat used vyrn_private. A fresh validated snapshot was then taken immediately before activation at /opt/vyrn/operations/deploy-20261015-e66e6a8/snapshot. Its parent directory is root-only and runtime.env is mode0600. These same-VPS snapshots are temporary and unreliable as disaster backups.

Only Core was recreated with up -d --no-deps --no-build core; PostgreSQL and the production media volume were retained. Running image/executable, schema9 and certificate-validated public readiness were verified. The unauthenticated new conversation-read route returns401. A separate post-deploy drill restored the pre-deploy snapshot, upgraded its clone to schema9, and successfully started the previous immutable Core image against that clone. Production remained on the new image throughout this rollback compatibility drill.

## Retained rollback candidate

The immediate verified rollback release is /opt/vyrn/releases/core-20261017-a05831a with image vyrn-core:rollback-20261019-28aa513, immutable sha256:012a0e1438c831b5b00b567745556a46689df55abbaa492cffe6fa4ed1eb151b and executable4b5a7059a5cc9e38569fee2361083d19b920104a338912030de6c705512222b5. This exact image was healthy before activation and is still retained. It preserves the selection endpoint/schema9 but uses the earlier Go1.26.8 runtime, so use it only as a temporary availability rollback. No production database restore/overwrite occurred. Automatic rollback was not triggered during this successful rollout; the earlier October16 DNS-failure rollback remains historical evidence.

Earlier reports also recorded /opt/vyrn/releases/core-20261011-a00a123 and image vyrn-core:rollback-20261015-e66e6a8, immutable sha256:2daeb2878a140b3233c30a5ebfc2ec14d20641ba3f5dbacf56f8e6f615bb6820. That executable157771edb9eb31f93eba73d6f44a6cda852be86791a289ee1a091a71d1928cda was tested only for readiness on an isolated restored schema9 clone. Their availability was not rechecked in this rollout; use the immediate verified candidate above. Full older-API compatibility with new media flows was not established. Preserve current data and restore into separate volumes if database recovery is necessary.

Dedicated key login was verified. Existing root/password access, SSH settings, firewall policy and unrelated services were preserved. Owner credentials live only in `/etc/vyrn/owner-access.json` mode 0600. Owner login, TOTP, all panel read endpoints, invite creation/revocation and session logout passed on the VPS. Bootstrap environment was removed. Containers use restart `unless-stopped` and Docker is enabled; whole-host reboot has not been tested.

The prebuilt image copies the executable with mode 0755. Normalize exported release directories to 0755 and static/source files to 0644 before Nginx serves them; secret files remain outside the release at 0600. Public Nginx config is `deployment/nginx-public.conf`, installed as `/etc/nginx/sites-available/vyrn-public`. All eight DNS A records were created DNS-only. The seven-domain `vyrn-public` certificate expires 2027-01-02; its webroot is `/var/lib/vyrn/acme`. Certbot timer and Nginx deploy hook are enabled; renewal dry-run passed. API/WS/media/push readiness and public admin/metrics blocking passed from Windows with certificate validation. Admin/web/updates hosts return503 pending their public rollout. TURN remains inactive. Do not start Caddy on the existing public ports.

## Prerequisites

Confirmed target: `217.18.90.114`, Ubuntu 24.04.3 LTS, SSH `root` on port `3939`, about 4 GB RAM and 40 GB disk. Dedicated key login is accepted. Never transfer the private half. The recorded host fingerprint was checked against the existing known-host entry.

Run read-only checks first: `/etc/os-release`, `uname`, RAM, disk, existing services, listeners, firewall, Docker, users, SSH effective settings and timezone. Preserve current services and access. Create a non-root administrative user and confirm its SSH key login in a separate session before changing any access policy. Root login and password authentication require separate explicit user approval to disable.

## Future standalone-host setup reference (not the active existing-Nginx deployment)

1. Configure `Asia/Tehran`, unattended security updates and Fail2ban on SSH port 3939.
2. Keep `3939/tcp` open before enabling UFW; allow `80/tcp` and `443/tcp`. Do not open relay ports before provider confirmation.
3. Install Docker Engine and Compose from Docker's official Ubuntu repository. Keep PostgreSQL unpublished. Restrict the admin panel to a verified operator CIDR or VPN.
4. Install source under `/opt/vyrn`; create `/etc/vyrn/runtime.env` mode 0600. Set a randomly generated URL-safe PostgreSQL password and administrator TOTP secret. Keep release keystores off this server.
5. Validate with `docker compose --env-file /etc/vyrn/runtime.env -f deployment/compose.yaml config -q`. Do not print expanded Compose configuration because it includes environment secrets.
6. Build with `docker compose --env-file /etc/vyrn/runtime.env -f deployment/compose.yaml build core`. Start PostgreSQL, run the bootstrap command in an empty database with bootstrap values supplied privately, remove bootstrap environment values, then start Core and Caddy.
7. Confirm health, authorized API tests, certificate renewal, reboot persistence, memory use and free disk. Keep a verified database backup before migrations. Migrations use checksums and cannot be silently modified after application.

## DNS and TLS

`scripts/set-cloudflare-env.ps1` prompts privately for the token. It only sets the current PowerShell process environment; launch the consuming process from that session. Required names: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ZONE_ID`.

`scripts/cloudflare-dns.ps1` performs read-only planning; `-Apply` creates absent records only. Existing conflicting records are preserved for review. All eight VYRN hostnames point to the supplied IPv4 and remain DNS-only, including TURN. Do not proxy the core endpoints through an external CDN.

Caddy automatically obtains and renews publicly trusted certificates after DNS and port reachability are correct. API/WS/media/push share Core. Admin routes are served only on the restricted administrator host. The public API virtual hosts reject `/v1/admin/*` and `/metrics`.

Monitor certificate expiry and renewal failures. An international outage can delay renewal; valid existing certificates must stay deployed. Never switch clients to an unvalidated certificate or raw IP to bypass TLS.

## TURN gate

Coturn is an inactive Compose profile named `calls`; its real config is ignored. Provider inbound UDP is unconfirmed. Voice/video feature flags remain false. After confirmation, test UDP and TCP on 3478, TLS on 5349 and the documented relay range `49160–49200/udp`; only then enable verified calling. Generate short-lived credentials server-side, never ship the shared TURN secret to clients. Credential issuance and WebRTC integration are still required.

## Rollback

Retain the previous immutable Core image and APK. Database migration rollback requires an inspected compatibility plan or restoration into a separate database, followed by a controlled switch; do not overwrite live data blindly. Keep host access and logs available during deployment failures.
