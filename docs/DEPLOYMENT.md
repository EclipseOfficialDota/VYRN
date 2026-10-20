# Self-hosting VYRN

This guide installs the current development source on a **fresh dedicated Ubuntu24.04 LTS host**. It does not certify a Stable release. Use a test instance before inviting users. Existing Nginx/Caddy sites must keep their ports and certificates; use an inspected proxy integration instead of starting a competing listener.

## 1. Host and Docker

Plan for at least4GiB RAM, two CPU cores and40GiB disk for a small evaluation, plus space for media and backup staging. These are starting budgets, not measured capacity guarantees. Core reserves10GiB free disk before accepting uploads. Build Android on a separate Windows workstation.

Install Git, Python3 and OpenSSL. Install Docker Engine, Buildx and the Compose plugin using [Docker's official Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/). Confirm:

```bash
docker version
docker compose version
```

Use a Docker-authorized administrator. Commands below assume that account can run Docker and uses Bash. Preserve your actual SSH port in the host/provider firewall before changing rules. Allow80/tcp and443/tcp for Caddy. PostgreSQL and Core must not be published directly. Docker-published ports can bypass ordinary UFW rules; review the firewall section of the Docker instructions.

## 2. Domain and DNS

Choose a service root, for example `vyrn.example.com`. Create DNS-only A records pointing to your host:

| Hostname | Role |
| --- | --- |
| `vyrn.example.com` | Small service identification endpoint |
| `api.vyrn.example.com` | Android REST API and health |
| `ws.vyrn.example.com` | WebSocket alias |
| `media.vyrn.example.com` | Media alias |
| `push.vyrn.example.com` | Core alias; not proof of a push delivery service |
| `admin.vyrn.example.com` | Restricted administration |
| `updates.vyrn.example.com` | Optional static update artifacts |

Do not create AAAA records unless IPv6 actually reaches this host. DNS and inbound80/443 must work before Caddy obtains certificates. The root hostname is not a browser messaging client.

Cloudflare is optional. For the existing PowerShell helper, set its two environment variables privately, then run `./scripts/cloudflare-dns.ps1 -Domain vyrn.example.com -IPv4 YOUR_PUBLIC_IPV4`. It plans first; `-Apply` creates missing records and preserves conflicting records for review. No TURN record is created while calls are unavailable.

## 3. Source and protected configuration

```bash
sudo install -d -m0755 -o "$USER" -g "$(id -gn)" /opt/vyrn
git clone https://github.com/EclipseOfficialDota/VYRN.git /opt/vyrn
cd /opt/vyrn
sudo install -d -m0700 /etc/vyrn
sudo install -m0600 .env.example /etc/vyrn/runtime.env
sudo python3 - <<'PY'
from pathlib import Path
import base64, secrets
p = Path('/etc/vyrn/runtime.env')
text = p.read_text()
text = text.replace('POSTGRES_PASSWORD=\n', 'POSTGRES_PASSWORD=' + secrets.token_hex(32) + '\n')
text = text.replace('VYRN_ADMIN_TOTP_SECRET=\n', 'VYRN_ADMIN_TOTP_SECRET=' + base64.b32encode(secrets.token_bytes(20)).decode() + '\n')
p.write_text(text)
p.chmod(0o600)
print('Generated database password and authenticator secret in the protected runtime file.')
PY
sudoedit /etc/vyrn/runtime.env
```

Do not repeat initialization over an existing configured installation. Set `VYRN_DOMAIN` to your root hostname **without https:// or a path**. Set `VYRN_ADMIN_ALLOWED_CIDRS` to your operator/VPN public IP in CIDR form, e.g. `YOUR_OPERATOR_IP/32`; multiple ranges are space-separated. Never use `0.0.0.0/0` as a workaround. The default loopback restriction intentionally denies remote browsers.

Privately import `VYRN_ADMIN_TOTP_SECRET` into your authenticator as a time-based, SHA1,6-digit,30-second entry. Keep host/workstation clocks synchronized. The secret belongs in the authenticator, **not** the panel's six-character code field. Keep the file outside Git, and do not print expanded Compose configuration.

For commands in this root shell:

```bash
sudo -i
cd /opt/vyrn
dc() { docker compose --env-file /etc/vyrn/runtime.env -f deployment/compose.yaml "$@"; }
dc config -q
mkdir -p artifacts/updates
dc build core
dc up -d --wait postgres
```

The function exists only in this shell; recreate it in a new session. `dc config -q` validates without printing secrets. Compose supplies the domain to Caddy and the matching admin Origin to Core. Caddy uses its documented [environment substitution](https://caddyserver.com/docs/caddyfile/concepts#environment-variables).

## 4. First administrator — once, empty database only

In the same root Bash session, enter your own account details. Password input is hidden and values are passed through the environment rather than command arguments:

```bash
read -r -p 'Administrator email: ' VYRN_BOOTSTRAP_EMAIL
read -r -s -p 'Administrator password (at least 8 characters): ' VYRN_BOOTSTRAP_PASSWORD
printf '\n'
VYRN_BOOTSTRAP_INVITE="$(openssl rand -hex 24)"
export VYRN_BOOTSTRAP_EMAIL VYRN_BOOTSTRAP_PASSWORD VYRN_BOOTSTRAP_INVITE
dc run --rm -e VYRN_BOOTSTRAP_EMAIL -e VYRN_BOOTSTRAP_PASSWORD -e VYRN_BOOTSTRAP_INVITE core bootstrap
# Continue only after bootstrap_complete and exit status0.
unset VYRN_BOOTSTRAP_EMAIL VYRN_BOOTSTRAP_PASSWORD VYRN_BOOTSTRAP_INVITE
dc up -d --wait core caddy
```

Use a unique strong password and store it in your password manager. Bootstrap migrates the database and creates the first administrator; it refuses a database with existing users. It also creates a temporary bootstrap invite (100 uses,7days); this procedure discards its plaintext. Create limited daily-use invites in Control instead. There is no shared/default administrator password and no automatic email verification/delivery service.

Do not rerun bootstrap to reset a password or delete database volumes to fix login. If setup fails, inspect the error category and fix configuration while preserving data.

## 5. Verify and sign in

Replace example hostnames in these commands:

```bash
curl --fail --show-error https://api.vyrn.example.com/health/ready
curl --silent --output /dev/null --write-out '%{http_code}\n' https://api.vyrn.example.com/v1/admin/dashboard
dc ps
dc logs --tail=80 core caddy
```

Readiness must succeed. Public API admin paths must return404; the panel hostname must reject a browser outside the allowed CIDRs with403. Logs should not be shared without inspection.

Open `https://admin.vyrn.example.com` from an allowed address. Log in using the bootstrap **email and password**, then enter the current six-digit Authenticator code. The administrative grant lasts5minutes; reverify when prompted. A code already used during its30-second interval is rejected; wait for a new one. Browser refresh may require login again because tokens stay in memory.

## 6. Give users access

In Control, open **Invites**, choose a small use count and expiry, and create an invite. Save the code when shown; only its hash is stored. Send the code privately to the intended person. You can revoke unused invites.

Build the client for **your API**, following [Android instructions](ANDROID_TESTING.md):

```powershell
./scripts/build-android.ps1 -Flavor dev -BuildType debug -ApiUrl https://api.vyrn.example.com
./scripts/verify-apks.ps1
```

For final distribution, use [production signing](RELEASE_SIGNING.md) and include `-ApiUrl https://api.vyrn.example.com` in the production build. Keep the same package identity and keystore for later updates. The existing project APK targets a different instance and has no server switcher.

The invited person installs the matching client, chooses registration, enters email, password, display name and invitation, and saves the recovery code when shown. Later sign-in uses email/password, not the public username. Accounts and invites do not work across instances. Users can then discover participants using supported app controls and create groups/private chats. Start with two test accounts and verify the particular messaging/media paths your users need before wider use.

## 7. Operate and update

Control can list users/groups, block/unblock other users, reset their passwords, revoke their sessions, issue/revoke invites and show audit events. Password resets revoke sessions; they are not a way to recover old locally held encryption keys. Do not promise administrators can read private-message plaintext. Group messages and service metadata have different privacy properties; see [security design](SECURITY.md).

Persist PostgreSQL, media and Caddy volumes. Never use `docker compose down -v` on a real instance. Arrange encrypted [offsite backup and restore rehearsals](BACKUP_RESTORE.md) before relying on stored data. A same-host snapshot is temporary and does not survive host loss.

Before upgrading: retain the current commit/image, take and verify a backup, inspect migrations/release notes, then test the candidate against a restored copy. Updating the source alone does not update a running container:

```bash
git fetch origin
# Select a reviewed commit/tag. Avoid an unattended moving-branch deployment.
git checkout YOUR_REVIEWED_COMMIT
dc build core
dc up -d --no-deps --wait core
curl --fail https://api.vyrn.example.com/health/ready
```

On failure, retain logs and data. Restore an earlier image only after verifying schema compatibility; database recovery should use a new database/volume and an inspected switch, not blind overwrite. Configure monitoring for readiness, disk, certificate expiry and backup failures.

## Existing reverse proxy and migration from earlier settings

`compose.existing-nginx.yaml` and the Nginx examples are optional integration templates, not an automatic installer. Review bindings, hostnames, certificates, forwarded IP trust and restricted panel access before use. They retain the original instance names as examples; replace them for your host. Do not start Caddy on occupied80/443.

Earlier deployments hardcoded the original project's domain in Compose/Caddy. On upgrade, explicitly set `VYRN_DOMAIN` to that same service root before recreating Caddy/Core. This documentation/config cleanup does not redeploy or change any running server.

Calls/TURN remain unavailable even if you open relay ports; inbound UDP confirmation and actual calling implementation/acceptance are separate gates.
