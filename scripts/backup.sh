#!/usr/bin/env sh
set -eu
umask 077
: "${RESTIC_REPOSITORY:?External S3-compatible repository required}"
: "${RESTIC_PASSWORD_FILE:?Offline-managed encryption password file required}"
case "$RESTIC_REPOSITORY" in s3:https://*) ;; *) echo 'External S3-compatible HTTPS repository required; same-disk backup is temporary and unreliable.' >&2; exit 1;; esac
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
state=${VYRN_BACKUP_STATE_DIR:-/var/lib/vyrn/backup}
mkdir -p "$state"
exec 9>"$state/backup.lock"
flock -n 9 || exit 1
tmp=$(mktemp -d "${TMPDIR:-/tmp}/vyrn-backup.XXXXXXXX")
finish() { result=$?; rm -rf -- "$tmp"; if [ "$result" -ne 0 ]; then date -u +%FT%TZ >"$state/last-failure"; logger -p daemon.err -t vyrn-backup 'Encrypted external backup FAILED'; fi; exit "$result"; }
trap finish EXIT HUP INT TERM
docker compose --env-file /etc/vyrn/runtime.env -f "$root/deployment/compose.yaml" exec -T postgres pg_dump -U vyrn -d vyrn -Fc >"$tmp/database.dump"
cp "$root/config/project.yaml" "$tmp/project.yaml"
cp "$root/deployment/Caddyfile" "$tmp/Caddyfile"
cp /etc/vyrn/runtime.env "$tmp/runtime.env"
(cd "$tmp" && sha256sum database.dump >database.sha256)
restic backup --quiet --host vyrn --tag daily "$tmp"
restic forget --quiet --host vyrn --tag daily --group-by host,tags --keep-daily 7 --keep-weekly 4 --prune
restic check --quiet
date -u +%FT%TZ >"$state/last-success"
logger -t vyrn-backup 'Encrypted external backup completed'
