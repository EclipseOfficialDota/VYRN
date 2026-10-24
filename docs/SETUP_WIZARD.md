# Server setup and terminal control

The English-only VYRN panel uses Python's standard library and Docker Compose. It installs the development preview, not a certified Stable Android release.

## Start

```sh
git clone https://github.com/EclipseOfficialDota/VYRN.git
cd VYRN
sudo bash setup.sh
```

The first root launch registers `/usr/local/bin/vyrn`. Afterward, reopen from any directory using **`vyrn`**; it requests sudo privileges if needed. Keep the checkout in place. After moving it, rerun `sudo bash setup.sh` from the new location. An unrelated existing `vyrn` command or symlink is preserved, not overwritten.

Update an unchanged checkout with `git pull --ff-only`, then `sudo bash setup.sh`. Read-only prerequisites: `bash setup.sh --check` (Python3 must already exist to avoid its optional install prompt). The interactive panel requires an SSH terminal and root/sudo. Android builds stay on your workstation.

## Supported hosts

| Distribution | Versions | Docker suites |
| --- | --- | --- |
| Ubuntu | 22.04, 24.04, 26.04, 26.10 | jammy, noble, resolute, stonking |
| Debian | 12, 13 | bookworm, trixie |

Use amd64/arm64 with systemd. Docker uses its matching official [Ubuntu](https://docs.docker.com/engine/install/ubuntu/) or [Debian](https://docs.docker.com/engine/install/debian/) repository/key. Other releases/derivatives are refused. Detection and command planning are tested; installation on every release has not been verified.

## Menu

The old flat menu has been replaced. Before configuration, only setup-related actions appear:

```text
NOT INSTALLED
 1  Easy install
 2  Check this server
 3  Setup help
 0  Exit
```

After the backend and public HTTPS have passed setup verification, the dashboard shows:

```text
INSTALLED
Core: Healthy | Database: Healthy
HTTPS: Last verified: <UTC time>

 1  Connection details
 2  Diagnose and repair
 3  Service controls
 4  Domain and HTTPS
 5  Administrator access
 6  Run guided setup checks again
 8  Remove services (keep data)
 9  Help and limitations
 0  Exit
```

The dashboard inspects actual container state whenever it returns. Missing/unhealthy/stopped containers or unfinished verification show **SETUP NEEDS ATTENTION**, with **Resume setup / finish verification** as the first action. Service/removal controls appear only when containers exist; administrator controls require a running Core. No external DNS/TLS requests are made merely to draw the menu: HTTPS displays the last successful verification time, not a claim of continuous monitoring. Older installations without a progress record need one verification run to establish that record.

Selections clear the screen; results remain until Enter. Cyan marks navigation, green success, yellow pending states and red failures/removal. Redirected output and `TERM=dumb` receive no color or clear codes. `NO_COLOR=1 vyrn` disables colors.

Diagnostics is read-only: OS/disk, local Docker/Compose, TCP listeners, protected settings, stack ownership, service status, DNS and public HTTPS. It continues after individual failures and does not print runtime secrets. No packages, firewall rules or services are changed by diagnostics.

## Installation

Easy install has six explicit stages: server prerequisites, Docker, protected instance settings, DNS, backend/bootstrap, and public HTTPS. A failed stage keeps the user on that stage with **Retry**, **Read-only diagnostics**, a relevant **Repair** when available, and **Pause setup**. Repairs are verified by rerunning the original stage check before proceeding. Docker repair validates the local context before starting the local daemon; DNS repair can create missing Cloudflare records; HTTPS repair configures supported host Nginx or reconciles standalone Core/Caddy.

The wizard does not blindly delete volumes, kill port listeners, edit firewall rules or upgrade an unsupported OS to make a check pass. DNS credentials, provider rules and conflicting unrelated sites can require operator correction. Pause/resume records the last stage in private `/etc/vyrn/setup-state.json` with status/time metadata only. Resume rechecks stages rather than trusting old success markers; existing settings/accounts are preserved. Setup is marked complete only after HTTPS passes. Backup/restore and source upgrades remain documented workflows rather than nonfunctional menu buttons.

The wizard checks host/disk, local engine, stack ownership and ports. It asks for domain, public IPv4 and operator IP/VPN CIDRs, then confirms before saving. Database/TOTP secrets are generated into `/etc/vyrn/runtime.env` (0600, private0700 directory); existing secrets are preserved.

Optional Cloudflare setup uses a hidden, unsaved token and preserves conflicting records. Alternatively create the seven printed DNS-only A records manually. DNS is checked before building. The wizard validates Compose, builds missing Core, starts PostgreSQL and queries actual database state. Administrator credentials are requested only for an empty database; passwords are hidden and passed through the environment, not argv. It starts selected services and verifies HTTPS without bypassing TLS.

### Existing reverse proxy

If80/443 are occupied, Install offers existing-proxy mode rather than stopping the listener. Accepting saves `VYRN_PROXY_MODE=existing` and uses `deployment/compose.setup-proxy.yaml`: PostgreSQL/Core only, source-based Core build and **127.0.0.1:18080**. Caddy is excluded from install/start/stop. Port18080 must be free when creating a new Core container.

Diagnostics leaves your proxy unchanged. In the HTTPS setup stage, select its host-Nginx repair action, or open **Diagnose and repair → Repair Core and host Nginx HTTPS** later. Caddy, Apache, container proxies and complex/conflicting configurations still use [manual integration](DEPLOYMENT.md#existing-reverse-proxy-and-migration-from-earlier-settings). Core readiness alone is not public HTTPS readiness.

### Automatic Nginx repair

The screenshot case where DNS passes but no Core container is running requires starting Core as well as configuring HTTPS. Nginx repair checks protected existing-proxy settings, local engine/checkout ownership, active host Nginx and all seven DNS names. It refuses an unrelated existing site that overlaps those names, a foreign installer-site file or unsupported proxy mode. The action previews its changes, asks for confirmation/Let's Encrypt terms acceptance and a renewal contact email.

It builds/starts missing backend services and bootstraps only an empty database, preserving existing accounts/secrets. Once local Core readiness passes, it installs Certbot if absent, copies only the three public admin assets into `/var/lib/vyrn/admin`, and creates its own `/etc/nginx/conf.d/vyrn-setup.conf`.

Certificate issuance uses Certbot's [webroot method](https://eff-certbot.readthedocs.io/en/stable/using.html#webroot), through the running Nginx. No service is stopped, disabled or killed to free80/443. It validates the site, verifies public HTTP challenge reachability for each name, obtains/reuses the instance certificate, validates/reloads HTTPS, verifies API readiness and enables `certbot.timer`. A deploy hook validates/reloads Nginx after future successful renewal. See [Nginx reload behavior](https://nginx.org/en/docs/beginners_guide.html#control).

API hosts deny administrative endpoints. The HTTPS admin hostname serves the copied assets and proxies `/v1/`, restricted to configured operator/VPN CIDRs. The backend remains on loopback; root/updates return503 because no public web client/update service is configured by this repair.

On an exception or Ctrl+C during proxy changes, the prior VYRN site file and mode are restored (or a newly created file is removed), then Nginx is validated/reloaded. An unsuccessful restoration reload is reported explicitly. This rollback covers the proxy file, not backend startup, package installs, copied public assets or issued certificates; those remain for retry. It cannot recover from power loss/SIGKILL. Provider firewall faults, broken IPv6/DNS and CA rate limits require correction before retry; the repair does not bypass TLS or change firewall rules. End-to-end issuance and renewal need host-specific verification.

Settings without a proxy-mode field retain standalone Caddy behavior. Do not switch an already-running installation by casually editing that field; inspect containers and migrate using the manual guide.

## Troubleshooting

Errors show a `NEXT` action. These cover common failures, not every possible host/provider fault.

| Problem | Next step |
| --- | --- |
| Port80/443 occupied | Diagnostics identifies listeners; accept existing-proxy mode on a new install. Preserve unrelated sites. |
| Port18080 occupied | Inspect its listener; use the correct existing checkout or resolve the conflict before installing Core. |
| No settings | Run Install / resume setup; never copy another server's secrets. |
| Insecure settings | Inspect root ownership, directory0700/file0600; never print/regenerate keys blindly. |
| Docker failure | Check `systemctl status docker`, local context, `docker compose version`, registry access and disk; retry after correction. |
| Remote Docker context | Unset `DOCKER_HOST`/`DOCKER_CONTEXT`; select a local Unix-socket context. Remote operations are refused. |
| Another checkout owns stack | Use its Compose working_dir; do not adopt/delete it from another checkout. |
| apt locks/errors | Check network/repositories and wait for apt/dpkg processes. Never delete locks or blindly remove container packages. |
| Low disk | Inspect `df -h`/`docker system df`; expand disk or remove reviewed unused files. Do not erase volumes. |
| DNS/Cloudflare | Run DNS records / check; verify A/AAAA/proxy records, zone/token scope and propagation. Conflicts remain untouched. |
| HTTPS pending / no Core container | Resume setup and use the failed HTTPS stage's repair action, or Diagnose and repair → host Nginx repair. Check DNS/inbound80/443 if challenge verification fails. Other proxies use manual setup. |
| Unhealthy service | Service status, Diagnostics, then inspect Compose logs privately. Correct database/config/disk issues and resume. |
| Authenticator denied | Current six-digit code, not the setup key. Check server `timedatectl status` and automatic phone time. |
| Admin403 | Use the allowed IP/VPN and inspect proxy restrictions; never allow the entire internet. |
| `vyrn` missing/stale | Run `sudo bash setup.sh` from the intended checkout; ensure `/usr/local/bin` is in PATH. Foreign commands are preserved. |
| Noninteractive input | Reconnect with `ssh -t`; do not pipe passwords. |

Firewall rules and SSH authentication are never changed. Preserve SSH when reviewing provider/host inbound80/443. Inspect logs locally before sharing them.

## Removal and recovery

Stop retains containers/data. Remove requires typing `REMOVE` and deletes only this instance's containers/networks. Accounts, messages, media, certificates, settings, DNS, Docker, images, checkout and launcher remain. Choose0 or leave confirmation blank to cancel. Local engine/ownership are checked immediately before removal. Run Install from the same checkout to recreate services using retained settings/data; this is not data erasure or migration rollback.

## Validation and limits

`python3 scripts/test-setup-server.py` checks release/repository selection, secrets/files, ownership, DNS/bootstrap, dynamic dashboards, stage retries/repairs/pause, private progress metadata, navigation/colors, launcher quoting/preservation, proxy selection, diagnostics, removal and Nginx repair. Repair tests exercise success, CA failure, invalid TLS configuration, HTTPS failure, interruption and renewal-timer failure with both new and existing site files. External operations are mocked. When Nginx/OpenSSL are available, generated HTTP/TLS configurations are also validated with real `nginx -t`, disposable certificates and unprivileged loopback ports. CI validates both Compose configurations and runs the existing isolated backend bootstrap/migration/health/restart test. Fresh-host installs, public issuance/renewal and provider reachability need host-specific verification.

Final Android signing/acceptance, external backup credentials/restore rehearsal and calls remain separate gates. The panel does not build APKs or collect keystores.
