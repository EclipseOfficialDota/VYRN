#!/usr/bin/env sh
# Validates a custom-format database dump against a separate disposable database.
set -eu
umask 077
: "${1:?Path to restored database.dump required}"
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
db="vyrn_restore_$(date +%s)"
compose() { docker compose --env-file /etc/vyrn/runtime.env -f "$root/deployment/compose.yaml" "$@"; }
compose exec -T postgres createdb -U vyrn "$db"
cleanup() { compose exec -T postgres dropdb -U vyrn --if-exists "$db"; }
trap cleanup EXIT HUP INT TERM
compose exec -T postgres pg_restore -U vyrn --exit-on-error --no-owner --dbname "$db" <"$1"
compose exec -T postgres psql -U vyrn -d "$db" -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS migration_count FROM schema_migrations; SELECT count(*) AS user_count FROM users; SELECT count(*) AS message_count FROM messages;'
echo 'Restore smoke test passed on isolated database.'
