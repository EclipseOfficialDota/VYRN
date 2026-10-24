#!/usr/bin/env bash
# CI/release maintainer tool; packages images only, never instance data or secrets.
set -euo pipefail
revision=${1:?Full tested commit required}
output=${2:?Output directory required}
[[ $revision =~ ^[a-f0-9]{40}$ ]] || { echo 'Expected a full commit SHA' >&2; exit 1; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p -- "$output"
output=$(cd -- "$output" && pwd)
core="ghcr.io/eclipseofficialdota/vyrn-lite:sha-$revision"
postgres='postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24'
caddy='caddy:2.10.2-alpine@sha256:4c6e91c6ed0e2fa03efd5b44747b625fec79bc9cd06ac5235a779726618e530d'
for arch in amd64 arm64; do
    bundle=$(mktemp -d)
    trap 'rm -rf -- "$bundle"' EXIT
    mkdir -p "$bundle/deployment" "$bundle/admin" "$bundle/artifacts/updates"
    cp "$root/deployment/compose.yaml" "$root/deployment/compose.lite.yaml" "$root/deployment/Caddyfile" "$bundle/deployment/"
    cp "$root/admin/index.html" "$root/admin/app.js" "$root/admin/style.css" "$bundle/admin/"
    cp "$root/docs/DOCKER_LITE.md" "$bundle/README.md"
    printf '%s\n' "$core" > "$bundle/image.ref"
    if [[ $arch == amd64 ]]; then
        runtime="vyrn-runtime-${revision:0:12}.tar.gz"
        tar -C "$bundle" -czf "$output/$runtime" .
        (cd "$output" && sha256sum "$runtime" > "$runtime.sha256")
    fi
    docker pull --platform "linux/$arch" "$core"
    docker pull --platform "linux/$arch" "$postgres"
    docker pull --platform "linux/$arch" "$caddy"
    for image in "$core" "$postgres" "$caddy"; do
        [[ $(docker image inspect --format '{{.Architecture}}' "$image") == "$arch" ]] || { echo 'Image architecture mismatch' >&2; exit 1; }
    done
    pg_tag="vyrn-offline/postgres:$arch-$revision"
    caddy_tag="vyrn-offline/caddy:$arch-$revision"
    docker tag "$postgres" "$pg_tag"
    docker tag "$caddy" "$caddy_tag"
    printf 'services:\n  postgres:\n    image: %s\n  caddy:\n    image: %s\n' "$pg_tag" "$caddy_tag" > "$bundle/deployment/compose.offline.yaml"
    docker image save --output "$bundle/images.tar" "$core" "$pg_tag" "$caddy_tag"
    (cd "$bundle" && sha256sum images.tar image.ref deployment/* admin/* > SHA256SUMS)
    archive="vyrn-offline-$arch-${revision:0:12}.tar.gz"
    tar -C "$bundle" -czf "$output/$archive" .
    (cd "$output" && sha256sum "$archive" > "$archive.sha256")
    rm -rf -- "$bundle"
    trap - EXIT
done
