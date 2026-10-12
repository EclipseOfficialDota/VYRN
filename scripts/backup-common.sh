#!/usr/bin/env sh
# Sourced by snapshot and isolated restore scripts.
compose() { docker compose --env-file "${VYRN_RUNTIME_ENV_FILE:-/etc/vyrn/runtime.env}" -f "$root/deployment/compose.yaml" "$@"; }
start_core() {
    compose start core >/dev/null || return 1
    attempts=0
    while [ "$attempts" -lt 30 ]; do
        health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Running}}{{end}}' "$core") || return 1
        case "$health" in healthy|true) return 0;; false|unhealthy) return 1;; esac
        attempts=$((attempts + 1)); sleep 2
    done
    return 1
}
media_index() {
    compose exec -T postgres psql -U vyrn -d "$1" -v ON_ERROR_STOP=1 -At -F "$(printf '\t')" -c 'SELECT storage_key::text,size,sha256 FROM media ORDER BY storage_key;'
    if [ "$(compose exec -T postgres psql -U vyrn -d "$1" -v ON_ERROR_STOP=1 -At -c "SELECT to_regclass('public.media_upload_parts') IS NOT NULL;")" = t ]; then
        compose exec -T postgres psql -U vyrn -d "$1" -v ON_ERROR_STOP=1 -At -F "$(printf '\t')" -c 'SELECT storage_key::text,size,sha256 FROM media_upload_parts ORDER BY storage_key;'
    fi
}
