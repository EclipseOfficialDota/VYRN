# Deployment runbook

Status (2026-10-15): **Core source e66e6a8 is active, schema is at nine migrations, Core and PostgreSQL are healthy, and public API TLS readiness passes. Physical-phone and final Release acceptance remain pending.** See [Persian owner guide](OWNER_GUIDE.fa.md).

## Active deployment

Host: Ubuntu 24.04.3 LTS, Docker 29.1.3 and Compose 2.40.3. The active release is /opt/vyrn/releases/core-20261015-e66e6a8, selected by /opt/vyrn/current. Use both deployment/compose.yaml and deployment/compose.existing-nginx.yaml with the protected /etc/vyrn/runtime.env. Core binds only 127.0.0.1:18080; PostgreSQL is unpublished. Existing Nginx retains ports 80/443 and the owner panel binds only 127.0.0.1:19091. Caddy's standalone-proxy profile remains excluded.

The October 15 release uses source e66e6a892643d6e5ca386d0ac5bbee2e89316d59. Running executable SHA256: c95b3454eef1f76d7fc9ea218826f0ec6df9ab6f4e84fed87f7dc387a2275846. Immutable image: sha256:0332c2f7887d52ec62648b1cc455d514da65b07f55dee2d738fe9b912d9ec503, tagged vyrn-core:e66e6a8 and vyrn-core:latest. Migrations 008_media_uploads.sql and 009_voice_duration.sql upgraded schema7 to schema9; all prior migrations were byte-compared unchanged. Admin source, Compose files and the prebuilt Dockerfile are unchanged from the prior release.

Before activation, a consistent database/media/config snapshot was restored into a disposable database and private media volume. Candidate startup applied migrations7→9 and passed readiness, media size/checksum and UID10001 checks while live schema remained7. The first rehearsal attempt used the edge network and could not reach PostgreSQL; the successful repeat used vyrn_private. A fresh validated snapshot was then taken immediately before activation at /opt/vyrn/operations/deploy-20261015-e66e6a8/snapshot. Its parent directory is root-only and runtime.env is mode0600. These same-VPS snapshots are temporary and unreliable as disaster backups.

Only Core was recreated with up -d --no-deps --no-build core; PostgreSQL and the production media volume were retained. Running image/executable, schema9 and certificate-validated public readiness were verified. The unauthenticated new conversation-read route returns401. A separate post-deploy drill restored the pre-deploy snapshot, upgraded its clone to schema9, and successfully started the previous immutable Core image against that clone. Production remained on the new image throughout this rollback compatibility drill.

## Retained rollback candidate

The prior release /opt/vyrn/releases/core-20261011-a00a123 and image vyrn-core:rollback-20261015-e66e6a8 (immutable sha256:2daeb2878a140b3233c30a5ebfc2ec14d20641ba3f5dbacf56f8e6f615bb6820) are retained. The old executable hash is157771edb9eb31f93eba73d6f44a6cda852be86791a289ee1a091a71d1928cda. Its readiness on restored schema9 was exercised in isolation; complete old-API compatibility with new media flows was not established. No production rollback or live database overwrite was performed. Assess those contracts before reverting; preserve current data and restore into separate volumes if database recovery is necessary.

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
