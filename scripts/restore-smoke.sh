#!/usr/bin/env sh
# Validates a custom-format database dump against a separate disposable database.
set -eu
umask 077
: "${1:?Restored snapshot directory or legacy database.dump required}"
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$root/scripts/backup-common.sh"
bundle=
dump=$1
if [ -d "$1" ]; then bundle=$1; python3 "$root/scripts/validate-backup.py" "$bundle"; dump="$bundle/database.dump"; fi
db="vyrn_restore_$(date +%s)_$$"
created=0
tmp=$(mktemp -d "${TMPDIR:-/tmp}/vyrn-restore.XXXXXXXX")
cleanup() { result=$?; trap - EXIT HUP INT TERM; if [ "$created" = 1 ]; then compose exec -T postgres dropdb -U vyrn --if-exists "$db" || result=1; fi; rm -rf -- "$tmp"; exit "$result"; }
trap cleanup EXIT HUP INT TERM
compose exec -T postgres createdb -U vyrn "$db"
created=1
compose exec -T postgres pg_restore -U vyrn --exit-on-error --no-owner --dbname "$db" <"$dump"
compose exec -T postgres psql -U vyrn -d "$db" -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS migration_count FROM schema_migrations; SELECT count(*) AS user_count FROM users; SELECT count(*) AS message_count FROM messages;'
if [ -n "$bundle" ]; then media_index "$db" >"$tmp/media-index.tsv"; python3 "$root/scripts/validate-backup.py" "$bundle" "$tmp/media-index.tsv" "$tmp/media"; echo 'Database and media restored and verified in isolated scratch space.'; else echo 'Legacy database-only restore passed; media recovery NOT verified.'; fi
