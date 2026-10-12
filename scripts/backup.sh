#!/usr/bin/env sh
set -eu
umask 077
: "${RESTIC_REPOSITORY:?External S3-compatible repository required}"
: "${RESTIC_PASSWORD_FILE:?Offline-managed encryption password file required}"
case "$RESTIC_REPOSITORY" in s3:https://*) ;; *) echo 'External S3-compatible HTTPS repository required; same-disk backup is temporary and unreliable.' >&2; exit 1;; esac
case "$RESTIC_REPOSITORY" in *@*|*\?*|*\#*) echo 'Keep credentials in private environment variables, not the repository URL.' >&2; exit 1;; esac
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$root/scripts/backup-common.sh"
state=${VYRN_BACKUP_STATE_DIR:-/var/lib/vyrn/backup}
mkdir -p "$state"
exec 9>"$state/backup.lock"
flock -n 9 || exit 1
tmp=$(mktemp -d "${TMPDIR:-/tmp}/vyrn-backup.XXXXXXXX")
resume=0
finish() { result=$?; trap - EXIT HUP INT TERM; if [ "$resume" = 1 ]; then start_core >/dev/null 2>&1 || result=1; fi; rm -rf -- "$tmp"; if [ "$result" -ne 0 ]; then date -u +%FT%TZ >"$state/last-failure"; logger -p daemon.err -t vyrn-backup 'Encrypted external backup FAILED'; fi; exit "$result"; }
trap finish EXIT HUP INT TERM
core=$(compose ps -q core)
case "$core" in ''|*[!a-f0-9]*) echo 'Exactly one running Core container required.' >&2; exit 1;; esac
[ "$(docker inspect --format '{{.State.Running}}' "$core")" = true ]
resume=1
compose stop -t 30 core >/dev/null
[ "$(docker inspect --format '{{.State.Running}}' "$core")" = false ]
# Stop writers/collection until both the database snapshot and media archive exist.
compose exec -T postgres pg_dump -U vyrn -d vyrn -Fc >"$tmp/database.dump"
media_index vyrn >"$tmp/media-index.tsv"
docker cp "$core:/media/." - >"$tmp/media.tar"
# ponytail: staging needs media-sized free disk; use filesystem snapshots when this exceeds capacity.
docker inspect --format '{{.Image}} {{.Config.Image}}' "$core" >"$tmp/core-image.txt"
start_core
resume=0
cp "$root/config/project.yaml" "$tmp/project.yaml"
cp "$root/deployment/Caddyfile" "$tmp/Caddyfile"
cp "$root/deployment/compose.yaml" "$tmp/compose.yaml"
cp "${VYRN_RUNTIME_ENV_FILE:-/etc/vyrn/runtime.env}" "$tmp/runtime.env"
owner_config=$(dirname -- "${VYRN_RUNTIME_ENV_FILE:-/etc/vyrn/runtime.env}")/owner-access.json
if [ -f "$owner_config" ]; then cp "$owner_config" "$tmp/owner-access.json"; fi
if [ -f /etc/nginx/sites-available/vyrn-public ]; then cp /etc/nginx/sites-available/vyrn-public "$tmp/nginx-public.conf"; fi
printf '%s\n' vyrn-backup-v2 >"$tmp/backup-format"
(cd "$tmp" && sha256sum database.dump media.tar media-index.tsv project.yaml Caddyfile compose.yaml runtime.env core-image.txt backup-format >checksums.sha256)
for optional in owner-access.json nginx-public.conf; do if [ -f "$tmp/$optional" ]; then (cd "$tmp" && sha256sum "$optional" >>checksums.sha256); fi; done
python3 "$root/scripts/validate-backup.py" "$tmp"
restic backup --quiet --host vyrn --tag daily "$tmp"
restic forget --quiet --host vyrn --tag daily --group-by host,tags --keep-daily 7 --keep-weekly 4 --prune
restic check --quiet
date -u +%FT%TZ >"$state/last-success"
logger -t vyrn-backup 'Encrypted external backup completed'
