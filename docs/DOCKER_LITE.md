# Lite containers and interrupted connectivity

Lite is a separate development-preview deployment for small workloads, alongside the original version. It uses prebuilt AMD64/ARM64 Core images, PostgreSQL and Caddy; no Go compiler, Node, Python, Git or Android SDK is needed on the runtime host. Docker Engine and Compose **2.24.4+** must already be installed. The original installer, menu and `backend/Dockerfile` remain in place. Lite uses its own `backend/Dockerfile.lite`, `compose.lite.yaml`, `vyrn-lite` image/project/volumes and a separate network subnet. Both standalone variants still require public80/443: running both on one host needs reviewed proxy/port routing, not two competing listeners. This is an additional deployment choice, not an automatic migration.

## Resource budget

| Component | Container memory ceiling | Configuration |
| --- | ---: | --- |
| Core | 256MiB | Go soft target192MiB, one CPU worker, one simultaneous password operation, four maximum DB connections |
| PostgreSQL | 192MiB | 32MiB shared buffers,16 connections,1MiB work memory, one autovacuum worker |
| Caddy | 96MiB | Go soft target64MiB, one CPU worker |

These are limits, **not measured idle consumption or a capacity guarantee**. Total ceilings are544MiB before Docker, kernel/page cache and the OS. Start evaluation on at least1GiB RAM; 512MiB hosts are not certified. Concurrent media/login load can exceed this budget and cause OOM/retries. CPU limits cap bursts; smaller memory targets trade CPU/throughput for memory.

Argon2id remains64MiB/three iterations. Lite reduces concurrent hashing, not password strength. PostgreSQL durability/fsync/WAL stay enabled. Media free-space reservation remains10GiB. Log rotation is2MiB ×2 files/service and health intervals30seconds. Calls remain disabled. Go's [memory limit is soft](https://go.dev/doc/gc-guide#Memory_limit), not a replacement for container limits or workload testing.

CI runs the normal backend/race/security gates plus Lite bootstrap, migrations, health, preserved accounts and restart under these limits. It records three scoped idle samples for Core/PostgreSQL in the `lite-runtime-samples` Actions artifact. Those exclude Caddy/host overhead and are not sustained-load or real-client acceptance.

## Online installation

Download a **vyrn-runtime-<commit>.tar.gz** and matching checksum from [Docker preview releases](https://github.com/EclipseOfficialDota/VYRN/releases). Verify against the checksum from the trusted release, then extract into a new directory. The bundle contains Compose/config/public admin files and an `image.ref` pointing to its exact tested commit tag.

In Bash, inside that directory:

```bash
umask 077
IMAGE=$(cat image.ref)
docker pull "$IMAGE"
# Initial installation only: do not overwrite an existing runtime.env.
test ! -e runtime.env || { echo 'Existing settings preserved'; exit 1; }
docker run --rm -e VYRN_IMAGE="$IMAGE" "$IMAGE" init-env > runtime.env
chmod 600 runtime.env
```

Edit `runtime.env` privately: set your real `VYRN_DOMAIN` (no scheme/path), and `VYRN_ADMIN_ALLOWED_CIDRS` to your operator public IP/VPN CIDRs. Keep generated secrets private; never commit this file. The default admin CIDR allows loopback only. Import `VYRN_ADMIN_TOTP_SECRET` privately into your authenticator (time based/SHA1/six digits/30seconds).

Create the seven DNS-only A records for the service root plus `api.`, `ws.`, `media.`, `push.`, `admin.`, `updates.`. Inbound80/443 and working DNS/CA access are needed for fresh HTTPS certificates. Remove stale AAAA records only when IPv6 is not configured. This standalone bundle requires free80/443; it never stops another proxy. Existing proxies need the documented manual integration.

```bash
dc() { docker compose --env-file runtime.env -f deployment/compose.yaml -f deployment/compose.lite.yaml "$@"; }
dc config -q
dc pull
dc up -d --no-build --wait postgres
read -r -p 'Administrator email: ' VYRN_BOOTSTRAP_EMAIL
read -r -s -p 'Administrator password (12+ characters): ' VYRN_BOOTSTRAP_PASSWORD
printf '\n'
VYRN_BOOTSTRAP_INVITE=$(docker run --rm "$IMAGE" token)
export VYRN_BOOTSTRAP_EMAIL VYRN_BOOTSTRAP_PASSWORD VYRN_BOOTSTRAP_INVITE
dc run --rm -T -e VYRN_BOOTSTRAP_EMAIL -e VYRN_BOOTSTRAP_PASSWORD -e VYRN_BOOTSTRAP_INVITE core bootstrap
unset VYRN_BOOTSTRAP_EMAIL VYRN_BOOTSTRAP_PASSWORD VYRN_BOOTSTRAP_INVITE
dc up -d --no-build --wait core caddy
dc ps
```

Stop on any failed command; do not proceed past a failed bootstrap. Bootstrap is for an empty database only. Existing accounts must never be reset by deleting volumes. Open `https://admin.YOUR_DOMAIN` from the allowed network, sign in and enter the current authenticator code. Invite users through Control with a matching APK; these server images do not contain an Android release.

For a quick server-image fetch: `docker pull ghcr.io/eclipseofficialdota/vyrn-lite:preview`. The preview tag moves after passing CI; prefer the commit tag in the downloaded bundle or pin the image digest. GHCR packages must be public for anonymous pulls; a workflow alone is not proof that visibility was enabled.

## Before a network outage

Pulling is impossible when all routes to a registry are blocked. Prepare **vyrn-offline-amd64-<commit>.tar.gz** (x86_64 servers) or **vyrn-offline-arm64-<commit>.tar.gz** (aarch64 servers) and its checksum in advance. Keep trusted copies on an accessible mirror/removable drive. Bundles contain all three runtime images, not just Core. They contain no instance database, media, passwords or keystores.

After trusted checksum verification and extraction:

```bash
sha256sum -c SHA256SUMS
docker image load -i images.tar
IMAGE=$(cat image.ref)
umask 077
test ! -e runtime.env || { echo 'Existing settings preserved'; exit 1; }
docker run --pull=never --rm -e VYRN_IMAGE="$IMAGE" "$IMAGE" init-env > runtime.env
```

Edit the domain/admin network as above. Include the offline override so pinned upstream image digests need not be fetched after `docker load`:

```bash
dc() { docker compose --env-file runtime.env -f deployment/compose.yaml -f deployment/compose.lite.yaml -f deployment/compose.offline.yaml "$@"; }
dc config -q
dc up -d --pull never --no-build --wait postgres
```

Use the same bootstrap instructions, adding `--pull never` to Compose `run` and `--pull=never` to `docker run` when generating the invite. Then start with `dc up -d --pull never --no-build --wait core caddy`. Images/startup do not require registry access. **Fresh public TLS issuance still requires CA connectivity**, even with all images offline; obtain valid certificates beforehand or use an inspected existing TLS proxy. This does not promise connectivity across a national network shutdown, DNS reachability or Android background push delivery.

## Existing installations and updates

Do not run the new bundle over unrelated installations or change PostgreSQL major versions/data directories. Retain Compose project names/volume mappings and verify a backup before migration. Core still uses the same schema and data formats; limits are an optional profile, not automatic production tuning. Image archives are distribution artifacts, not data backups. Use scoped resource measurements and load tests before choosing production limits.

Publishing is gated on CI success. The workflow publishes `preview` and `sha-<full commit>` multiarchitecture images and marks downloadable bundles as server development previews, never a Stable Android release.
