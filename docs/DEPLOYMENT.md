# Deployment runbook

Status (2026-10-04): **VPS provisioned; Core and PostgreSQL healthy; owner panel active through SSH only. Public DNS/TLS and public Android connectivity remain pending.** See [Persian owner guide](OWNER_GUIDE.fa.md).

## Active deployment

The actual host is Ubuntu 24.04.3 LTS, with Docker 29.1.3 and Compose 2.40.3 installed from Ubuntu packages. Source release: `/opt/vyrn/releases/vps-initial-20261004`, linked as `/opt/vyrn/current`. Run Compose with both `deployment/compose.yaml` and `deployment/compose.existing-nginx.yaml` and `/etc/vyrn/runtime.env`. Core listens only on `127.0.0.1:18080`; PostgreSQL is unpublished. Caddy is excluded by its `standalone-proxy` profile because existing Nginx uses 80/443. The owner panel Nginx site listens only on `127.0.0.1:19091`.

Dedicated key login was verified. Existing root/password access, SSH settings, firewall policy and unrelated services were preserved. Owner credentials live only in `/etc/vyrn/owner-access.json` mode 0600. Owner login, TOTP, all panel read endpoints, invite creation/revocation and session logout passed on the VPS. Bootstrap environment was removed. Containers use restart `unless-stopped` and Docker is enabled; whole-host reboot has not been tested.

The prebuilt image copies the executable with mode 0755. Normalize exported release directories to 0755 and static/source files to 0644 before Nginx serves them; secret files remain outside the release at 0600. Public Nginx VYRN hosts and matching certificates must still be configured after DNS. Do not start Caddy on the existing public ports.

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
